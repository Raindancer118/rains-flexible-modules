package de.raindancer.modules.voicebridge.store;

import de.raindancer.core.data.store.YamlStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The bot tokens, kept out of the settings on purpose: everything in settings is shown on the
 * {@code /settings} screens and written into a file people paste into support chats.
 *
 * <p>Read from {@value #ENVIRONMENT} first, so a container can keep it out of the database, then from this server's
 * {@code discord-tokens.yml} document ({@code /documents export} to edit it). The document is this server's own, not
 * the network's: a bot token carries one voice connection, so each server that bridges has its own bot. The 1.x
 * {@code discord-token.txt} is brought into it once and kept renamed {@code *.imported-2.0.0}.
 */
public final class BotTokens {

    public static final String ENVIRONMENT = "RAINS_VOICEBRIDGE_TOKEN";

    public enum Source { ENVIRONMENT, DOCUMENT, NONE }

    private static final List<String> TEMPLATE = List.of(
            "The Discord bot tokens for Rain's Voice Bridge go into the list below.",
            "The first token is the main bot.",
            "",
            "1. https://discord.com/developers/applications -> New Application -> Bot -> Reset Token.",
            "2. Invite the bot: OAuth2 -> URL Generator -> scope \"bot\", permissions \"Connect\" and \"Speak\".",
            "3. Add the token, import the document (/documents import), then /voicebridge reconnect.",
            "",
            "Proximity mode: every further token is one more bot, and each bot carries one Discord",
            "user at a time. Five extra tokens = five people on Discord in proximity chat at once.",
            "Each needs its own application (step 1) and invite (step 2).",
            "",
            "Anybody holding a token controls its bot. Never share this document.",
            "Alternatively set the environment variable RAINS_VOICEBRIDGE_TOKEN; it wins over this document.");

    private final YamlStore document;
    private final Path oldFile;
    private final Function<String, String> environment;
    private volatile Source source = Source.NONE;

    public BotTokens(YamlStore document, Path oldFile, Function<String, String> environment) {
        this.document = document;
        this.oldFile = oldFile;
        this.environment = environment;
    }

    public static BotTokens in(Path folder) {
        return new BotTokens(new YamlStore(folder.resolve("discord-tokens.yml")), folder.resolve("discord-token.txt"),
                System::getenv);
    }

    /** The main bot's token, or empty. Leaves an explained template the first time there is nothing. */
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
        var yaml = document.readIfReadable()
                .orElseThrow(() -> new IllegalStateException("the token document could not be read"));
        List<String> tokens = clean(yaml.getStringList("tokens"));
        if (tokens.isEmpty()) {
            tokens = importOldFile();
        }
        if (tokens.isEmpty() && !document.exists()) {
            writeTemplate();
        }
        source = tokens.isEmpty() ? Source.NONE : Source.DOCUMENT;
        return tokens;
    }

    public Source source() {
        return source;
    }

    private List<String> importOldFile() {
        if (oldFile == null || !Files.isRegularFile(oldFile)) {
            return List.of();
        }
        try {
            List<String> found = clean(Files.readAllLines(oldFile).stream()
                    .filter(line -> !line.strip().startsWith("#")).toList());
            if (found.isEmpty() || !document.update(yaml -> yaml.set("tokens", found))) {
                return found;
            }
            Files.move(oldFile, oldFile.resolveSibling(oldFile.getFileName() + ".imported-2.0.0"));
            return found;
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException("could not read " + oldFile, unreadable);
        }
    }

    private void writeTemplate() {
        document.write(yaml -> {
            yaml.options().setHeader(TEMPLATE);
            yaml.set("tokens", List.of());
        });
    }

    private static List<String> clean(List<String> lines) {
        return lines.stream().map(String::strip).filter(token -> !token.isEmpty()).toList();
    }
}
