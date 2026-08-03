package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

/**
 * How bad a reason is, as three ranks rather than a number somebody argues about.
 *
 * <p>Three because that is how many a person can hold in their head while clicking, and because the
 * only thing severity is <em>for</em> is sorting the reason list and colouring its buttons: the actual
 * lengths come from each reason's own ladder, which is a decision an owner makes per offence rather
 * than per rank.
 */
public enum Severity {

    /** Annoying. The first one usually costs somebody half an hour. */
    MINOR("Minor", 1, Material.LIME_DYE, "green"),

    /** Deliberate, and somebody had to be told. */
    SERIOUS("Serious", 2, Material.ORANGE_DYE, "gold"),

    /** The ones a ladder ends permanently. */
    SEVERE("Severe", 3, Material.RED_DYE, "red");

    private final String title;
    private final int weight;
    private final Material icon;
    private final String colour;

    Severity(String title, int weight, Material icon, String colour) {
        this.title = title;
        this.weight = weight;
        this.icon = icon;
        this.colour = colour;
    }

    /** What to call it on screen. */
    public String describe() {
        return title;
    }

    /** For ordering a list of reasons: the worst last, so a misclick costs the least. */
    public int weight() {
        return weight;
    }

    public Material icon() {
        return icon;
    }

    /** The MiniMessage colour name, so every screen agrees what "severe" looks like. */
    public String colour() {
        return colour;
    }
}
