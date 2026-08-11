package de.raindancer.modules.chained.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Two players, chained together — independent of any speedrun run. A pair can exist with no run
 * going at all, which is the state right after an admin makes one and before {@code /chain start}.
 *
 * @param a           one player
 * @param b           the other
 * @param maxDistance blocks; how far apart {@code a} and {@code b} may go before further
 *                    separation is refused
 */
public record ChainPair(UUID a, UUID b, double maxDistance) {

    public ChainPair {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.equals(b)) {
            throw new IllegalArgumentException("a chain needs two different players");
        }
        if (Double.isNaN(maxDistance) || Double.isInfinite(maxDistance) || maxDistance <= 0) {
            throw new IllegalArgumentException(
                    "maxDistance must be a positive, finite number of blocks");
        }
    }

    /** Whether this player is either side of the pair. */
    public boolean involves(UUID player) {
        return a.equals(player) || b.equals(player);
    }

    /**
     * The other side of the pair.
     *
     * @throws IllegalArgumentException if {@code player} is neither {@link #a} nor {@link #b}
     */
    public UUID otherOf(UUID player) {
        if (a.equals(player)) {
            return b;
        }
        if (b.equals(player)) {
            return a;
        }
        throw new IllegalArgumentException(player + " is not part of this pair");
    }

    public ChainPair withMaxDistance(double distance) {
        return new ChainPair(a, b, distance);
    }
}
