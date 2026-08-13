package de.raindancer.modules.mannequin.model;

/**
 * The running tally of damage dealt to one mannequin: kept in memory only, since none of it needs
 * to survive a restart — a reset button on {@code StatsScreen} is meant to be the only way it
 * empties, and a fresh session on every boot reads exactly the same to a player as one that was
 * quietly cleared to zero.
 *
 * <h2>Why time is passed in rather than read here</h2>
 * So {@code rules.ComboWindowRule} and this record's own {@link #hit} can be unit tested without a
 * clock: the caller supplies "now", the same way {@code Verdict}-style rules take their inputs
 * rather than reaching for {@code System.currentTimeMillis()} themselves.
 */
public record TrainingSession(double totalDamage, long hitCount, int comboStreak,
                              int longestCombo, long lastHitAt) {

    public static final TrainingSession EMPTY = new TrainingSession(0.0, 0, 0, 0, 0L);

    public TrainingSession {
        totalDamage = Math.max(0.0, totalDamage);
        hitCount = Math.max(0, hitCount);
        comboStreak = Math.max(0, comboStreak);
        longestCombo = Math.max(0, longestCombo);
    }

    /**
     * Records one more hit.
     *
     * @param damage        this hit's final damage
     * @param at             when it landed
     * @param continuesCombo whatever {@code ComboWindowRule#continuesCombo} decided, given this
     *                       session's own {@link #lastHitAt} and {@code at}
     */
    public TrainingSession hit(double damage, long at, boolean continuesCombo) {
        int streak = continuesCombo ? comboStreak + 1 : 1;
        return new TrainingSession(totalDamage + Math.max(0.0, damage), hitCount + 1, streak,
                Math.max(longestCombo, streak), at);
    }

    public double averageDamage() {
        return hitCount == 0 ? 0.0 : totalDamage / hitCount;
    }
}
