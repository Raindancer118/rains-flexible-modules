package de.raindancer.modules.speedrun;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * What a practice run starts with — {@code practice-kit}. Any kit makes a run a practice run: it is
 * kept on its own leaderboard, never beside runs that had to earn their pearls.
 */
public enum SpeedrunPracticeKit {
    /** A real run. */
    NONE("No kit", Map.of()),
    /** Enough to craft the eyes: practise the stronghold and the dragon, skip the nether. */
    BLAZE_AND_PEARLS("Blaze rods and pearls", Map.of(Material.BLAZE_ROD, 7, Material.ENDER_PEARL, 14)),
    /** Eyes ready to throw: practise finding the stronghold. */
    EYES_OF_ENDER("Eyes of ender", Map.of(Material.ENDER_EYE, 14)),
    /** Practise the dragon: eyes, beds for the one-cycle, blocks and food. */
    DRAGON_FIGHT("Dragon fight", Map.of(Material.ENDER_EYE, 14, Material.WHITE_BED, 6,
            Material.COBBLESTONE, 64, Material.COOKED_BEEF, 32, Material.IRON_SWORD, 1));

    private final String label;
    private final Map<Material, Integer> items;

    SpeedrunPracticeKit(String label, Map<Material, Integer> items) {
        this.label = label;
        this.items = items;
    }

    public String label() {
        return label;
    }

    /** What is handed out, material by amount, in a stable order. */
    public List<Map.Entry<Material, Integer>> items() {
        return items.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    public boolean isPractice() {
        return this != NONE;
    }

    /** The kit of this name — as a run's category keeps it — if it is one. */
    public static java.util.Optional<SpeedrunPracticeKit> byName(String name) {
        for (SpeedrunPracticeKit kit : values()) {
            if (kit.name().equalsIgnoreCase(name)) {
                return java.util.Optional.of(kit);
            }
        }
        return java.util.Optional.empty();
    }
}
