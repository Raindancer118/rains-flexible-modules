package de.raindancer.modules.cosmetics.model;

import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/**
 * The keys of what can be bought. A key is stored in the player's unlocks, so it must never change once
 * a server has sold one.
 */
public final class Unlock {

    public static final String NAME_COLOUR = "name.colour";
    public static final String NAME_GRADIENT = "name.gradient";
    public static final String NAME_ANY_COLOUR = "name.any-colour";
    public static final String NAME_ANIMATED = "name.animated";
    public static final String PARTICLES = "particles";
    public static final String TELEPORT = "teleport";

    /** What name styles are sold under, in the economy's books. */
    public static final String SOURCE_NAMES = "names.style";
    public static final String SOURCE_COSMETICS = "cosmetics.buy";

    private Unlock() {
    }

    public static String decoration(TextDecoration decoration) {
        return "decoration." + decoration.name().toLowerCase(Locale.ROOT);
    }

    public static String preset(String id) {
        return "preset." + id.toLowerCase(Locale.ROOT);
    }

    /** Which economy source a purchase is booked under. */
    public static String sourceOf(String key) {
        return key.equals(PARTICLES) || key.equals(TELEPORT) ? SOURCE_COSMETICS : SOURCE_NAMES;
    }
}
