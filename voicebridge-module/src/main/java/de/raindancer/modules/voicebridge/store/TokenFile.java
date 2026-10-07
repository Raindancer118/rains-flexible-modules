package de.raindancer.modules.voicebridge.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Function;

/**
 * The bot token, kept out of the settings on purpose: everything in settings is shown on the
 * {@code /settings} screens and written into a file people paste into support chats.
 *
 * <p>Read from {@value #ENVIRONMENT} first, so a container can keep it out of the volume, then
 * from a file only the server's own user can read.
 */
public final class TokenFile {

    public static final String ENVIRONMENT = "RAINS_VOICEBRIDGE_TOKEN";

    public enum Source { ENVIRONMENT, FILE, NONE }

    private static final String TEMPLATE = """
            # The Discord bot token for Rain's Voice Bridge goes on its own line below this text.
            #
            # 1. https://discord.com/developers/applications -> New Application -> Bot -> Reset Token.
            # 2. Invite the bot: OAuth2 -> URL Generator -> scope "bot", permissions "Connect" and "Speak".
            # 3. Paste the token below, then run /voicebridge reconnect in game or in the console.
            #
            # Anybody holding this token controls the bot. Never share this file.
            # Alternatively set the environment variable RAINS_VOICEBRIDGE_TOKEN; it wins over this file.
            """;

    private final Path file;
    private final Function<String, String> environment;
    private volatile Source source = Source.NONE;

    public TokenFile(Path file, Function<String, String> environment) {
        this.file = file;
        this.environment = environment;
    }

    public static TokenFile in(Path folder) {
        return new TokenFile(folder.resolve("discord-token.txt"), System::getenv);
    }

    /** The token, or empty. Writes the explained template the first time there is no file. */
    public String read() {
        String fromEnvironment = environment.apply(ENVIRONMENT);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            source = Source.ENVIRONMENT;
            return fromEnvironment.strip();
        }
        try {
            if (Files.notExists(file)) {
                writeTemplate();
                source = Source.NONE;
                return "";
            }
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    source = Source.FILE;
                    return trimmed;
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not read " + file, unreadable);
        }
        source = Source.NONE;
        return "";
    }

    public Source source() {
        return source;
    }

    public Path file() {
        return file;
    }

    private void writeTemplate() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, TEMPLATE);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
