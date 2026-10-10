package de.raindancer.modules.roles.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What a role can do in the game besides its shop perks. Each has a ceiling well under anything that would
 * change how the game plays — a role is a flavour, and roles.yml cannot make it more.
 */
public enum AbilityKind {
    /** Hunger drains slower. */
    HUNGER(30, "Hunger drains %d%% slower"),
    /** A chance of one more crop from a ripe one. */
    HARVEST(30, "%d%% chance of an extra crop from ripe crops"),
    /** A chance of one more piece of food from an animal killed. */
    BUTCHER(30, "%d%% chance of extra food from animals"),
    /** A chance of one more drop from ore — a little fortune, never for silk touch. */
    FORTUNE(20, "%d%% chance of an extra drop from ore"),
    /** Less fall damage. */
    FALLS(40, "%d%% less fall damage"),
    /** Faster on foot. */
    SPEED(10, "%d%% faster on foot"),
    /** Reaches blocks further away. */
    REACH(25, "%d%% further reach for blocks"),
    /** A chance a tool takes no wear. */
    TOOLS(30, "%d%% chance tools take no wear"),
    /** More experience from orbs. */
    XP(25, "%d%% more experience"),
    /** More damage to monsters — never to players. */
    MONSTERS(15, "%d%% more damage to monsters"),
    /** Minecarts ridden go faster. */
    CARTS(30, "Minecarts you ride go %d%% faster");

    private final int most;
    private final String wording;

    AbilityKind(int most, String wording) {
        this.most = most;
        this.wording = wording;
    }

    public int most() {
        return most;
    }

    public String says(int percent) {
        return String.format(Locale.ROOT, wording, percent);
    }

    /** How roles.yml names it: "hunger", "monsters". */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<AbilityKind> byKey(String key) {
        for (AbilityKind kind : values()) {
            if (kind.key().equalsIgnoreCase(key)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
