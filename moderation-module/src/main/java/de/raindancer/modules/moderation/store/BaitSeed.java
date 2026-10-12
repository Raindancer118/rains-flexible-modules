package de.raindancer.modules.moderation.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * The secret behind where the bait veins lie. Whoever knows it can tell every bait from real ore, so
 * it is drawn at random, kept in the plugin folder, and never shown anywhere.
 */
public final class BaitSeed {

    static final String FILE = "xray-bait-seed";

    private BaitSeed() {
    }

    public static long load(Path dataFolder) {
        Path file = dataFolder.resolve(FILE);
        try {
            if (Files.exists(file)) {
                try {
                    return Long.parseLong(Files.readString(file).trim());
                } catch (NumberFormatException unreadable) {
                    // Drawn afresh below: the layer moves once, which costs nothing but old baits.
                }
            }
            long seed = new SecureRandom().nextLong();
            Files.createDirectories(dataFolder);
            Files.writeString(file, Long.toString(seed));
            return seed;
        } catch (IOException failed) {
            throw new UncheckedIOException("cannot keep the bait seed in " + file, failed);
        }
    }
}
