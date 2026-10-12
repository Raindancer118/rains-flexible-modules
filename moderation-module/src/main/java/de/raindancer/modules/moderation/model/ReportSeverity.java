package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

/** How bad a report looks at first glance — a colour before anybody reads a word. */
public enum ReportSeverity {

    LOW("Low", "<green>", Material.LIME_CONCRETE),
    MEDIUM("Medium", "<yellow>", Material.YELLOW_CONCRETE),
    HIGH("High", "<gold>", Material.ORANGE_CONCRETE),
    CRITICAL("Critical", "<red>", Material.RED_CONCRETE);

    private final String title;
    private final String colour;
    private final Material icon;

    ReportSeverity(String title, String colour, Material icon) {
        this.title = title;
        this.colour = colour;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    /** A MiniMessage colour tag. */
    public String colour() {
        return colour;
    }

    public Material icon() {
        return icon;
    }

    /** Four squares, as many filled as it is bad. */
    public String bar() {
        return colour + "■".repeat(ordinal() + 1) + "<dark_gray>" + "■".repeat(3 - ordinal());
    }

    public static ReportSeverity of(int level) {
        return values()[Math.max(0, Math.min(values().length - 1, level - 1))];
    }
}
