package de.raindancer.modules.guilds.model;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/** How somebody gets into a guild. An invitation works in every mode. */
public enum JoinMode {
    INVITE("By invitation only", Material.IRON_DOOR),
    APPLY("Players may apply", Material.WRITABLE_BOOK),
    OPEN("Anybody may join", Material.OAK_DOOR);

    private final String title;
    private final Material icon;

    JoinMode(String title, Material icon) {
        this.title = title;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public JoinMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<JoinMode> named(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        for (JoinMode mode : values()) {
            if (mode.key().equalsIgnoreCase(typed.trim())) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
