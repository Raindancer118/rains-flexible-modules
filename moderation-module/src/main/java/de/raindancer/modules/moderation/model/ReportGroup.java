package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

/** The first question a reporting player answers: what kind of thing happened. */
public enum ReportGroup {

    CHEATING("Cheating & exploits", "Cheating", Material.DIAMOND_PICKAXE),
    GRIEFING("Griefing & stealing", "Griefing", Material.TNT),
    CHAT("Chat & behaviour", "Chat", Material.OAK_HANGING_SIGN),
    BUILDS("Builds & names", "Builds", Material.PAINTING),
    OTHER("Something else", "Other", Material.PAPER);

    private final String title;
    private final String shortTitle;
    private final Material icon;

    ReportGroup(String title, String shortTitle, Material icon) {
        this.title = title;
        this.shortTitle = shortTitle;
        this.icon = icon;
    }

    /** For a window's title, which the game cuts off after a few words. */
    public String shortTitle() {
        return shortTitle;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }
}
