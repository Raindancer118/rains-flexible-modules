package de.raindancer.modules.oneblock.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An island as BentoBox and AOneBlock kept it, as much as moving it over needs.
 *
 * @param members    the team without the owner: BentoBox's member rank and up
 * @param x          the island's centre, where AOneBlock's block stands
 * @param protection the largest protection range it ever had — nobody could build past it
 * @param range      half the distance to the next island
 * @param lifetime   AOneBlock's count of every block ever broken, across its phase loops
 * @param spawn      the team's own spawn on it, if they set one
 */
public record BentoIsland(String bentoId, UUID owner, List<UUID> members, int x, int y, int z, int protection,
                          int range, long lifetime, Optional<Spawn> spawn, long created) {

    public BentoIsland {
        members = List.copyOf(members);
    }
}
