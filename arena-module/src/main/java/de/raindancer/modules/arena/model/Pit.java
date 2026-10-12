package de.raindancer.modules.arena.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a fight happens: the floor fighters may not leave, and where each of them starts.
 *
 * @param floor null until somebody has marked it
 */
public record Pit(String id, String world, Box floor, List<Place> spawns) {

    public Pit {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a pit needs a name");
        }
        id = id.toLowerCase(Locale.ROOT);
        spawns = List.copyOf(spawns);
    }

    public static Pit named(String id, String world) {
        return new Pit(id, world, null, List.of());
    }

    public Pit withFloor(Box marked) {
        if (!marked.world().equalsIgnoreCase(world)) {
            throw new IllegalArgumentException("pit " + id + " is in " + world + ", not " + marked.world());
        }
        return new Pit(id, world, marked, spawns);
    }

    public Pit withSpawn(Place spawn) {
        if (!spawn.world().equalsIgnoreCase(world)) {
            throw new IllegalArgumentException("pit " + id + " is in " + world + ", not " + spawn.world());
        }
        List<Place> more = new ArrayList<>(spawns);
        more.add(spawn);
        return new Pit(id, world, floor, more);
    }

    public Pit withoutSpawns() {
        return new Pit(id, world, floor, List.of());
    }

    /** A floor and at least two places to start from — the least a duel needs. */
    public boolean isReady() {
        return floor != null && spawns.size() >= 2;
    }
}
