package de.raindancer.modules.wallsroads.rules;

import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.store.Occupancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Whether somebody may change a block that a wall or a road is made of.
 *
 * <h2>Why a standing wall has to be protected at all</h2>
 * A build is undone from its snapshot, which records what was there <em>before</em>. Let anybody mine
 * a wall and the snapshot no longer describes the world: tearing that wall down afterwards puts
 * stone back into the hole they dug, in blocks that were never there. So the protection is not a
 * courtesy — it is what keeps every teardown honest.
 */
public final class ProtectRule {

    /**
     * @param ownerOf     the owner of a structure by id — empty when the structure is not in the
     *                    registry, which is a broken state and is treated as protected rather than
     *                    as free, since the alternative is that anything the module loses track of
     *                    becomes everybody's to mine
     * @param mayManageAny whether this player holds the manage-anything permission
     */
    public boolean mayChange(Occupancy occupancy, Spot spot, Function<String, Optional<UUID>> ownerOf,
                             UUID player, boolean mayManageAny) {
        Optional<String> owner = occupancy.ownerOf(spot);
        if (owner.isEmpty()) {
            return true;
        }
        if (mayManageAny) {
            return true;
        }
        return ownerOf.apply(owner.get()).map(player::equals).orElse(false);
    }
}
