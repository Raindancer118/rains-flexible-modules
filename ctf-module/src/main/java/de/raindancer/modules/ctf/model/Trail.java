package de.raindancer.modules.ctf.model;

import org.bukkit.Material;

/** What a flag carrier leaves behind them. Unlocked by level; the default is their team colour. */
public enum Trail {
    TEAM("Team colour", Material.WHITE_DYE, "dust", 1),
    FLAME("Flames", Material.BLAZE_POWDER, "flame", 3),
    HEARTS("Hearts", Material.POPPY, "heart", 5),
    NOTES("Notes", Material.NOTE_BLOCK, "note", 7),
    SOULS("Souls", Material.SOUL_LANTERN, "soul_fire_flame", 10),
    CHERRY("Cherry petals", Material.CHERRY_LEAVES, "cherry_leaves", 13),
    END_ROD("Stardust", Material.END_ROD, "end_rod", 16),
    TOTEM("Totem", Material.TOTEM_OF_UNDYING, "totem_of_undying", 20);

    private final String title;
    private final Material icon;
    private final String particle;
    private final int level;

    Trail(String title, Material icon, String particle, int level) {
        this.title = title;
        this.icon = icon;
        this.particle = particle;
        this.level = level;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public String particle() {
        return particle;
    }

    /** The level it unlocks at. */
    public int level() {
        return level;
    }
}
