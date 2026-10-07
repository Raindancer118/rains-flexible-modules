package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

/**
 * The four buttons that change somebody's body: heal, feed, hurt, starve. The typed commands live in
 * Player Utils now; the player page's tools screen still offers them, through Core's {@code PlayerAdmin}.
 *
 * <p>Hurt and starve sit a rank higher than heal and feed: restoring somebody is unremarkable, taking
 * half of their health silently is a way to kill a player in a fight they were winning.
 */
public enum Vital {

    HEAL("heal", "Restores somebody to full health", ModerationPermission.HEAL,
            Material.GOLDEN_APPLE, false),
    FEED("feed", "Fills somebody's hunger bar", ModerationPermission.FEED,
            Material.COOKED_BEEF, false),
    HURT("hurt", "Takes half of somebody's health", ModerationPermission.HURT,
            Material.IRON_SWORD, true),
    STARVE("starve", "Empties most of somebody's hunger bar", ModerationPermission.STARVE,
            Material.ROTTEN_FLESH, true);

    /** How much hurt takes: half of a full bar. Enough to matter, not enough to kill outright. */
    public static final double HURT_HEARTS = 10.0;

    /** How far starve drops them: to the point where sprinting stops, not to zero. */
    public static final int STARVE_TO = 6;

    private final String word;
    private final String description;
    private final ModerationPermission permission;
    private final Material icon;
    private final boolean harmful;

    Vital(String word, String description, ModerationPermission permission, Material icon,
          boolean harmful) {
        this.word = word;
        this.description = description;
        this.permission = permission;
        this.icon = icon;
        this.harmful = harmful;
    }

    public String word() {
        return word;
    }

    public String describe() {
        return description;
    }

    public ModerationPermission permission() {
        return permission;
    }

    public Material icon() {
        return icon;
    }

    /** Whether this takes something away, so a screen can colour it and confirm it. */
    public boolean harmful() {
        return harmful;
    }

    /** Where the wording lives. Never a bare {@code on}/{@code off}: those are YAML booleans. */
    public String messageKey() {
        return "moderation.vitals." + word;
    }
}
