package de.raindancer.modules.ctf.model;

import org.bukkit.Material;

/** What lies about in the middle of a map to be picked up. */
public enum PowerUp {
    SPEED("Speed", Material.SUGAR, "<aqua>"),
    STRENGTH("Strength", Material.BLAZE_POWDER, "<red>"),
    SHIELD("Shield", Material.HEART_OF_THE_SEA, "<yellow>"),
    GRAPPLE("Grappling hook", Material.FISHING_ROD, "<gold>"),
    DOUBLE_JUMP("Double jump", Material.RABBIT_FOOT, "<green>"),
    HEAL("Heal", Material.GLISTERING_MELON_SLICE, "<light_purple>");

    private final String title;
    private final Material icon;
    private final String colour;

    PowerUp(String title, Material icon, String colour) {
        this.title = title;
        this.icon = icon;
        this.colour = colour;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    /** MiniMessage colour tag for its label. */
    public String colour() {
        return colour;
    }

    public static java.util.Optional<PowerUp> parse(String typed) {
        for (PowerUp powerUp : values()) {
            if (powerUp.name().equalsIgnoreCase(typed)) {
                return java.util.Optional.of(powerUp);
            }
        }
        return java.util.Optional.empty();
    }
}
