package de.raindancer.modules.performance.model;

import java.util.List;
import java.util.UUID;

/**
 * One chunk with too much of something, and what could be done about it.
 *
 * @param land   the claim it is on, or empty for unclaimed ground
 * @param owners who owns that claim
 * @param names  the owners' names, for a report a person reads
 */
public record Finding(Kind kind, ChunkCensus where, String what, int count, List<Fix> fixes,
                      String land, List<UUID> owners, List<String> names) {

    public enum Kind {
        ANIMAL_CROWD, ITEM_PILE, MONSTER_CROWD, VILLAGER_CROWD, BLOCK_ENTITY_CLUSTER, ENTITY_CROWD
    }

    public Finding {
        fixes = List.copyOf(fixes);
        land = land == null ? "" : land;
        owners = owners == null ? List.of() : List.copyOf(owners);
        names = names == null ? List.of() : List.copyOf(names);
    }

    public Finding(Kind kind, ChunkCensus where, String what, int count, List<Fix> fixes) {
        this(kind, where, what, count, fixes, "", List.of(), List.of());
    }

    /** The same finding, on a claim. */
    public Finding on(String claim, List<UUID> claimOwners, List<String> ownerNames) {
        return new Finding(kind, where, what, count, fixes, claim, claimOwners, ownerNames);
    }

    public String landName() {
        return land.isEmpty() ? "unclaimed" : land;
    }

    /** The same kind of thing in the same chunk is the same finding, from one report to the next. */
    public String key() {
        return kind + "@" + where.world() + "/" + where.chunkX() + "/" + where.chunkZ() + "/" + what;
    }
}
