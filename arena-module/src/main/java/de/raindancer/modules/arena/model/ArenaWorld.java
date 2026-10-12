package de.raindancer.modules.arena.model;

import java.util.Locale;

/**
 * One world of the arena: its name, the schematic it was built from (a file in the plugin's {@code maps} folder), and
 * the box the map stands in — which is what its border is drawn around.
 */
public record ArenaWorld(String name, String map, Box mapBox) {

    public ArenaWorld {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an arena world needs a name");
        }
        if (!mapBox.world().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("the map of " + name + " is in " + mapBox.world());
        }
    }

    public boolean is(String world) {
        return world != null && name.toLowerCase(Locale.ROOT).equals(world.toLowerCase(Locale.ROOT));
    }
}
