package de.raindancer.modules.voicebridge.store;

import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The bot tokens, kept out of the settings (everything in settings is shown on the {@code /settings} screens and
 * written into files people paste into support chats) and out of documents (which {@code /documents} can list and
 * export): they live in the network's secret store.
 *
 * <p>Read from {@value #ENVIRONMENT} first, so a container can keep it out of the database, then from this server's
 * secret {@code voicebridge-tokens-<serverId>}, one token per line. The secret is this server's own, not the
 * network's: a bot token carries one voice connection, so each server that bridges has its own bot. Tokens an older
 * {@code discord-tokens.yml} document holds, or the 1.x {@code discord-token.txt} (kept renamed
 * {@code *.imported-2.0.0}), are moved into the secret and the document is rewritten without them.
 */
public final class BotTokens {

    public static final String ENVIRONMENT = "RAINS_VOICEBRIDGE_TOKEN";

    public enum Source { ENVIRONMENT, DATABASE, NONE }

    private static final List<String> TEMPLATE = List.of(
            "The Discord bot tokens for Rain's Voice Bridge are not kept in this document: they live in the",
            "network's secret store, which /documents cannot list or export.",
            "",
            "1. https://discord.com/developers/applications -> New Application -> Bot -> Reset Token.",
            "2. Invite the bot: OAuth2 -> URL Generator -> scope \"bot\", permissions \"Connect\" and \"Speak\".",
            "3. From the server console: /voicebridge token add <token>, then /voicebridge reconnect.",
            "   (/voicebridge token clear removes this server's tokens.)",
            "",
            "The first token is the main bot. Proximity mode: every further token is one more bot, and each bot",
            "carries one Discord user at a time. Five extra tokens = five people on Discord in proximity chat at once.",
            "Each needs its own application (step 1) and invite (step 2).",
            "",
            "Alternatively set the environment variable RAINS_VOICEBRIDGE_TOKEN; it wins over the stored tokens.",
            "Tokens put into the list below are moved into the secret store the next time they are read.");

    private final Secrets secrets;
    private final String secretName;
    private final YamlStore document;
    private final Path oldFile;
    private final Function<String, String> environment;
    private final LogChannel log;
    private volatile Source source = Source.NONE;

    public BotTokens(Secrets secrets, String serverId, YamlStore document, Path oldFile,
                     Function<String, String> environment, LogChannel log) {
        this.secrets = secrets;
        this.secretName = "voicebridge-tokens-" + serverId;
        this.document = document;
        this.oldFile = oldFile;
        this.environment = environment;
        this.log = log;
    }

    public static BotTokens in(Path folder, Secrets secrets, String serverId, LogChannel log) {
        return new BotTokens(secrets, serverId, new YamlStore(folder.resolve("discord-tokens.yml")),
                folder.resolve("discord-token.txt"), System::getenv, log);
    }

    /** The main bot's token, or empty. */
    public String read() {
        List<String> all = readAll();
        return all.isEmpty() ? "" : all.getFirst();
    }

    /** Every token: the first is the main bot, any after it are proximity lines, one listener each. */
    public List<String> readAll() {
        String fromEnvironment = environment.apply(ENVIRONMENT);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            source = Source.ENVIRONMENT;
            return Arrays.stream(fromEnvironment.split(",")).map(String::strip).filter(token -> !token.isEmpty()).toList();
        }
        migrate();
        List<String> tokens = stored();
        source = tokens.isEmpty() ? Source.NONE : Source.DATABASE;
        return tokens;
    }

    public Source source() {
        return source;
    }

    public boolean environmentSet() {
        String fromEnvironment = environment.apply(ENVIRONMENT);
        return fromEnvironment != null && !fromEnvironment.isBlank();
    }

    /** Appends a token to this server's tokens. @return whether it was stored */
    public boolean add(String token) {
        List<String> merged = new ArrayList<>(stored());
        if (!merged.contains(token.strip())) {
            merged.add(token.strip());
        }
        return secrets.putText(secretName, String.join("\n", merged));
    }

    /** @return whether the database could be written */
    public boolean clear() {
        return secrets.remove(secretName);
    }

    private List<String> stored() {
        return clean(secrets.text(secretName).map(text -> List.of(text.split("\n"))).orElse(List.of()));
    }

    private void migrate() {
        var yaml = document.readIfReadable();
        if (yaml.isEmpty()) {
            if (stored().isEmpty()) {
                throw new IllegalStateException("the token document could not be read");
            }
            return;
        }
        List<String> legacy = clean(yaml.get().getStringList("tokens"));
        boolean fromFile = legacy.isEmpty() && oldFile != null && Files.isRegularFile(oldFile);
        if (fromFile) {
            legacy = readOldFile();
        }
        if (legacy.isEmpty()) {
            if (!document.exists()) {
                writeTemplate();
            }
            return;
        }
        List<String> merged = new ArrayList<>(stored());
        legacy.stream().filter(token -> !merged.contains(token)).forEach(merged::add);
        if (!secrets.putText(secretName, String.join("\n", merged))) {
            log.warn("The Discord bot tokens could not be moved into the database; they stay where they are for now.");
            return;
        }
        if (fromFile) {
            retireOldFile();
        }
        if (writeTemplate()) {
            log.info("The Discord bot tokens moved into the network's secret store.");
        }
    }

    private List<String> readOldFile() {
        try {
            return clean(Files.readAllLines(oldFile).stream().filter(line -> !line.strip().startsWith("#")).toList());
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException("could not read " + oldFile, unreadable);
        }
    }

    private void retireOldFile() {
        try {
            Files.move(oldFile, oldFile.resolveSibling(oldFile.getFileName() + ".imported-2.0.0"));
        } catch (IOException failure) {
            log.warn("{} could not be renamed ({}); its tokens are in the database now.", oldFile, failure.getMessage());
        }
    }

    private boolean writeTemplate() {
        return document.write(yaml -> {
            yaml.options().setHeader(TEMPLATE);
            yaml.set("tokens", List.of());
        });
    }

    private static List<String> clean(List<String> lines) {
        return lines.stream().map(String::strip).filter(token -> !token.isEmpty()).toList();
    }
}
