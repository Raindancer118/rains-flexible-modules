package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

/**
 * Where a report has got to.
 *
 * <h2>Why four and not a boolean</h2>
 * {@link #CLAIMED} is the one that earns its place: without it, two moderators walk to the same grief
 * and the second one arrives to find nothing to do. {@link #REJECTED} earns its place for the other
 * end — "nobody looked at it" and "somebody looked and there was nothing in it" are different answers,
 * and the player who filed it deserves the second one.
 */
public enum ReportState {

    /** Filed, and nobody has picked it up. */
    OPEN("Waiting", Material.PAPER),

    /** Somebody is on it. */
    CLAIMED("Being looked at", Material.SPYGLASS),

    /** Looked at, and something was done. */
    RESOLVED("Dealt with", Material.LIME_DYE),

    /** Looked at, and there was nothing in it. */
    REJECTED("Nothing in it", Material.GRAY_DYE);

    private final String title;
    private final Material icon;

    ReportState(String title, Material icon) {
        this.title = title;
        this.icon = icon;
    }

    /** What to call it on screen. */
    public String describe() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    /** Whether it is finished with — the two that leave the queue. */
    public boolean isClosed() {
        return this == RESOLVED || this == REJECTED;
    }
}
