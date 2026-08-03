package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

/**
 * Where somebody stands, in one word.
 *
 * <h2>Why this exists</h2>
 * "Is this player all right?" is the question a moderator actually has, and answering it from a raw
 * record means reading every entry and doing the arithmetic on each — still in force? lifted? three
 * years ago? Done by eye, at speed, that arithmetic is wrong often enough that people stop checking and
 * ask in staff chat instead, which is how a server ends up with one person who knows everything.
 *
 * <p>Four ranks, ordered. {@link #weight()} makes them comparable, so a list of players can be sorted
 * by who needs attention.
 */
public enum Standing {

    /** Nothing in force, and nothing recent. */
    GOOD("in good standing", 0, "green", Material.LIME_DYE),

    /** Nothing in force, but something happened recently enough to be worth knowing. */
    WATCHED("worth an eye", 1, "yellow", Material.SPYGLASS),

    /** Here, but not free to do everything — muted or frozen. */
    RESTRICTED("under a restriction", 2, "gold", Material.PAPER),

    /** Not welcome. */
    BANNED("banned", 3, "red", Material.BARRIER);

    private final String description;
    private final int weight;
    private final String colour;
    private final Material icon;

    Standing(String description, int weight, String colour, Material icon) {
        this.description = description;
        this.weight = weight;
        this.colour = colour;
        this.icon = icon;
    }

    /**
     * How it reads in a sentence about a person.
     *
     * <p>Written to follow a name: <em>"Raindancer118 is <b>in good standing</b>."</em> — so it is a
     * phrase rather than a label, and no caller has to fix up the grammar.
     */
    public String describe() {
        return description;
    }

    /** Worst last, so two players can be compared and a list can be sorted by who needs looking at. */
    public int weight() {
        return weight;
    }

    /** The MiniMessage colour name, so every screen and every message agree what "banned" looks like. */
    public String colour() {
        return colour;
    }

    public Material icon() {
        return icon;
    }
}
