package de.raindancer.modules.speedrun;

import org.bukkit.World;

import java.util.Locale;

/**
 * The three worlds one speedrun is played across: the lobby world itself and the two dimensions
 * named after it, {@code <name>_nether} and {@code <name>_the_end}.
 *
 * <h2>Why the module owns these names at all</h2>
 * Minecraft links dimensions by the folder layout of the <em>primary</em> level, and only for that
 * one. A world made at runtime — which is exactly what this module's lobby world is — has no
 * dimensions of its own, so a nether portal built in it drops the player into the server's own
 * nether, and walking back out of that puts them in the server's own overworld: outside the race,
 * in a world the reset will never touch. That was a real run. The names here are the group the
 * module keeps travel inside, and {@link SpeedrunPortalListener} is what enforces it.
 *
 * <p>The {@code _nether} / {@code _the_end} suffixes are Minecraft's own convention rather than an
 * invention here, so a server owner reading their world folder sees the shape they expect.
 */
record SpeedrunWorlds(String overworld) {

    static SpeedrunWorlds around(String overworld) {
        return new SpeedrunWorlds(overworld == null ? "" : overworld.toLowerCase(Locale.ROOT));
    }

    String nether() {
        return overworld + "_nether";
    }

    String theEnd() {
        return overworld + "_the_end";
    }

    /** Whether {@code worldName} is any of the three — the test for "this travel is ours to redirect". */
    boolean contains(String worldName) {
        if (worldName == null) {
            return false;
        }
        String name = worldName.toLowerCase(Locale.ROOT);
        return name.equals(overworld) || name.equals(nether()) || name.equals(theEnd());
    }

    /** Which of the three a trip into {@code environment} should land in, or {@code null} for a custom one. */
    String inDimension(World.Environment environment) {
        if (environment == null) {
            return null;
        }
        return switch (environment) {
            case NORMAL -> overworld;
            case NETHER -> nether();
            case THE_END -> theEnd();
            default -> null;   // a datapack dimension; nothing sensible to redirect it to
        };
    }
}
