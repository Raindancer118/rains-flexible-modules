package de.raindancer.modules.mannequin.rules;

/**
 * Whether a hit's final damage would have killed a bare, unarmored, full-health player.
 *
 * <p>20.0 is vanilla's default player max health — {@code Attributes.MAX_HEALTH}'s base value —
 * with nothing absorbing it: no armor, no potion resistance, no absorption hearts. A hit at or
 * above that would take such a player from full health to zero in one blow.
 */
public final class LethalHitRule implements IMannequinRule {

    /** Vanilla's default player max health, unarmored and unbuffed. */
    public static final double UNARMORED_PLAYER_MAX_HEALTH = 20.0;

    public boolean wouldHaveKilledUnarmoredPlayer(double finalDamage) {
        return finalDamage >= UNARMORED_PLAYER_MAX_HEALTH;
    }

    @Override
    public String describe() {
        return "whether a hit's damage is at least a bare, full-health player's entire 20 hearts";
    }
}
