package de.raindancer.modules.guilds.model;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * What a guild spends its perk points on. One point a rank; each rank needs the guild to have reached a level —
 * {@code firstLevel} for the first, {@code levelsPerRank} more for each after — so points alone never buy the top
 * of the tree.
 */
public enum Perk {

    VAULT("Guild vault", Material.ENDER_CHEST, 6, 2, 3,
            "A shared vault, a row of nine stacks per rank."),
    HASTE("Miner's haste", Material.GOLDEN_PICKAXE, 2, 4, 8,
            "Members dig faster: Haste I, then Haste II."),
    LUCK("Fortune's favour", Material.RABBIT_FOOT, 2, 6, 8,
            "Members are luckier with loot and fishing: Luck I, then II."),
    SCHOLAR("Scholar", Material.EXPERIENCE_BOTTLE, 3, 3, 4,
            "Members get 10% more of the vanilla experience they pick up, per rank."),
    PROSPEROUS("Prosperous", Material.EMERALD, 3, 5, 5,
            "The guild earns 5% more guild experience, per rank."),
    RECRUITER("Recruiter", Material.PLAYER_HEAD, 3, 4, 4,
            "Room for 2 more members, per rank."),
    DIPLOMAT("Diplomat", Material.WHITE_BANNER, 2, 7, 6,
            "One more ally slot, per rank."),
    PATHFINDER("Pathfinder", Material.ENDER_PEARL, 2, 4, 6,
            "The guild home: half the warm-up and wait, then none.");

    private final String title;
    private final Material icon;
    private final int maxRank;
    private final int firstLevel;
    private final int levelsPerRank;
    private final String description;

    Perk(String title, Material icon, int maxRank, int firstLevel, int levelsPerRank, String description) {
        this.title = title;
        this.icon = icon;
        this.maxRank = maxRank;
        this.firstLevel = firstLevel;
        this.levelsPerRank = levelsPerRank;
        this.description = description;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public int maxRank() {
        return maxRank;
    }

    public String description() {
        return description;
    }

    /** The guild level a rank needs. */
    public int levelFor(int rank) {
        return firstLevel + Math.max(0, rank - 1) * levelsPerRank;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Perk> named(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        for (Perk perk : values()) {
            if (perk.key().equalsIgnoreCase(typed.strip()) || perk.title().equalsIgnoreCase(typed.strip())) {
                return Optional.of(perk);
            }
        }
        return Optional.empty();
    }
}
