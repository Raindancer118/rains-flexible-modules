package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.platform.log.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * The secret behind where the bait veins lie. Whoever knows it can tell every bait from real ore, so
 * it is drawn at random, kept in the network's database as one secret for every server, and never shown
 * anywhere.
 */
public final class BaitSeed {

    static final String NAME = "moderation-bait-seed";
    /** The 1.x file, a seed as text; brought into the database on first use. */
    static final String FILE = "xray-bait-seed";
    private static final int LENGTH = 16;

    private BaitSeed() {
    }

    public static long load(Secrets secrets, Path dataFolder) {
        Optional<byte[]> kept = Optional.empty();
        try {
            kept = secrets.bytes(NAME, LENGTH, dataFolder.resolve(FILE));
        } catch (UnsupportedOperationException noSecrets) {
            // A core without a database: handled like one that cannot be reached.
        }
        if (kept.isEmpty()) {
            Log.of("moderation").warn("The bait seed could not be read from the network's database; this server "
                    + "uses a new one until it restarts, so its baits lie elsewhere meanwhile.");
            return new SecureRandom().nextLong();
        }
        return seedOf(kept.get());
    }

    /** An old file's text is the seed it always was, so baits stay where they were; fresh bytes are folded into one. */
    static long seedOf(byte[] secret) {
        try {
            return Long.parseLong(new String(secret, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException notText) {
            ByteBuffer bytes = ByteBuffer.allocate(Math.max(16, secret.length)).put(secret);
            bytes.rewind();
            return bytes.getLong() ^ bytes.getLong();
        }
    }
}
