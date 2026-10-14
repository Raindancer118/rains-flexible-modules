package de.raindancer.modules.moderation.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What this module keeps levels of, with the numbers that decide what a level leads to. The checks themselves are
 * Grim's: each flag Grim raises lands in the family its check belongs to ({@link GrimFamily}); the client policy
 * is this module's own rule. Setbacks and cancels stay Grim's, so no type here acts on a flag itself.
 *
 * <p>{@code kickAt}/{@code banAt} of 0 means never.
 *
 * @param decayPerMinute how much violation level a player loses per clean minute
 */
public enum CheckType {

    GRIM_MOVEMENT("movement", Category.MOVEMENT, "Movement",
            "Grim's movement checks: its simulation of every move, ground spoofing, no-fall, timer, phase, elytra, vehicles.",
            10, 60, 120, 6),
    GRIM_COMBAT("combat", Category.COMBAT, "Combat",
            "Grim's combat checks: reach, hitboxes, aim, attacks a vanilla client never makes.",
            5, 40, 80, 4),
    GRIM_WORLD("world", Category.WORLD, "World",
            "Grim's world checks: breaking and placing blocks faster, further or in ways a vanilla client cannot.",
            5, 40, 0, 4),
    GRIM_INVENTORY("inventory", Category.INVENTORY, "Inventory",
            "Grim's inventory checks: clicking and moving items in ways a vanilla client cannot.",
            5, 0, 0, 4),
    GRIM_PACKETS("packets", Category.PACKETS, "Packets",
            "Grim's packet checks: packets a vanilla client never sends, in an order it never sends them.",
            3, 30, 60, 4),

    CLIENT("client", Category.CLIENT, "Client", "A client brand or channel this server does not allow.",
            1, 0, 0, 0);

    private final String key;
    private final Category category;
    private final String title;
    private final String description;
    private final int alertAt;
    private final int kickAt;
    private final int banAt;
    private final double decayPerMinute;

    CheckType(String key, Category category, String title, String description, int alertAt, int kickAt, int banAt,
              double decayPerMinute) {
        this.key = key;
        this.category = category;
        this.title = title;
        this.description = description;
        this.alertAt = alertAt;
        this.kickAt = kickAt;
        this.banAt = banAt;
        this.decayPerMinute = decayPerMinute;
    }

    public String key() {
        return key;
    }

    public Category category() {
        return category;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public int alertAt() {
        return alertAt;
    }

    public int kickAt() {
        return kickAt;
    }

    public int banAt() {
        return banAt;
    }

    public double decayPerMinute() {
        return decayPerMinute;
    }

    /** Whether one flag is already proof: staff hear it at once. */
    public boolean impossible() {
        return this == CLIENT;
    }

    /** By key ({@code movement}) or by name ({@code GRIM_MOVEMENT}), case-insensitive. */
    public static Optional<CheckType> find(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (CheckType type : values()) {
            if (type.key.equals(wanted) || type.name().toLowerCase(Locale.ROOT).equals(wanted)
                    || type.title.toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
