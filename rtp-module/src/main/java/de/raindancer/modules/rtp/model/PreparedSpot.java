package de.raindancer.modules.rtp.model;

import de.raindancer.core.world.safety.Spot;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Somewhere already found and checked, waiting to be given to a player.
 *
 * <h2>Why {@code usedBy} rather than removing it after one trip</h2>
 * A spot found once is safe for far more than one player, and a pool of three thousand emptying itself
 * one trip at a time would be a pool that never actually saves any searching. What it must not do is
 * send the same player to somewhere they have already been dropped by chance before — this is what
 * makes that a fact about the spot rather than something a caller has to remember to check.
 *
 * <h2>Why the height stored here is not necessarily still right</h2>
 * It was safe when {@code RtpLocationPoolService} found it. Between then and whoever actually lands
 * here, a tree can grow, a player can build, a chunk can regenerate under a datapack change — so this
 * is a starting point for {@code Safety} to check again, never a promise on its own.
 */
public record PreparedSpot(String id, String world, int x, int y, int z, Instant preparedAt,
                           Set<UUID> usedBy) {

    public PreparedSpot {
        usedBy = usedBy == null ? Set.of() : Set.copyOf(usedBy);
    }

    /** As Core's own coordinate type, for handing straight to {@code Safety}. */
    public Spot spot() {
        return new Spot(world, x, y, z);
    }

    /** Whether this player has already been sent here. */
    public boolean usedBy(UUID player) {
        return player != null && usedBy.contains(player);
    }

    /** The same spot, with one more player who has been sent here. */
    public PreparedSpot markUsedBy(UUID player) {
        if (player == null || usedBy.contains(player)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(usedBy);
        next.add(player);
        return new PreparedSpot(id, world, x, y, z, preparedAt, next);
    }
}
