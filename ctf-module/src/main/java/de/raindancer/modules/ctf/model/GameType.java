package de.raindancer.modules.ctf.model;

import org.bukkit.Material;

/** How a round is played. */
public enum GameType {
    /** Each side has a flag; bring theirs home while yours is home. */
    CLASSIC("Classic", Material.RED_BANNER, "Steal their flag, bring it home —", "while yours is still at home."),
    /** One side defends a flag, the other attacks it; at half time they swap. */
    ONE_FLAG("One flag", Material.SHIELD, "One side defends, one attacks.", "Half time: swap. Most captures wins."),
    /** One flag in the middle; carry it into the other side's base. */
    NEUTRAL("Neutral flag", Material.WHITE_BANNER, "One flag in the middle of the map.", "Carry it into their base."),
    /** Classic, with each side a guild. */
    GUILD_WAR("Guild war", Material.BLACK_BANNER, "Classic rules, guild against guild.", "Needs two guilds online.");

    private final String title;
    private final Material icon;
    private final String[] lines;

    GameType(String title, Material icon, String... lines) {
        this.title = title;
        this.icon = icon;
        this.lines = lines;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public java.util.List<String> lines() {
        return java.util.List.of(lines);
    }

    /** Whether every side has a flag of its own at its base. */
    public boolean everySideHasAFlag() {
        return this == CLASSIC || this == GUILD_WAR;
    }

    public static java.util.Optional<GameType> parse(String typed) {
        if (typed == null) {
            return java.util.Optional.empty();
        }
        String name = typed.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (GameType type : values()) {
            if (type.name().equals(name)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }
}
