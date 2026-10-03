package de.raindancer.modules.manhunt.setup;

import java.util.List;
import java.util.Optional;

/**
 * The goals worth one click — every one an advancement vanilla has, from the full run down to a
 * fifteen-minute sprint. Anything else stays one {@code /manhunt goal set <key>} away.
 */
public final class Goals {

    /** One goal: its advancement, what it is called, and the item that shows it. */
    public record Goal(String key, String label, String icon, String length) {
    }

    private static final List<Goal> ALL = List.of(
            new Goal("minecraft:end/kill_dragon", "Kill the dragon", "DRAGON_HEAD", "The full run"),
            new Goal("minecraft:story/enter_the_end", "Enter the End", "END_PORTAL_FRAME", "Almost the full run"),
            new Goal("minecraft:story/follow_ender_eye", "Find the stronghold", "ENDER_EYE", "About an hour"),
            new Goal("minecraft:nether/obtain_blaze_rod", "Get a blaze rod", "BLAZE_ROD", "Half an hour or so"),
            new Goal("minecraft:nether/find_fortress", "Find a fortress", "NETHER_BRICKS", "Twenty minutes or so"),
            new Goal("minecraft:story/enter_the_nether", "Enter the Nether", "OBSIDIAN", "A quick one"),
            new Goal("minecraft:story/mine_diamond", "Mine a diamond", "DIAMOND", "A quick one"));

    private Goals() {
    }

    public static List<Goal> all() {
        return ALL;
    }

    public static Optional<Goal> byKey(String key) {
        return ALL.stream().filter(goal -> goal.key().equals(key)).findFirst();
    }
}
