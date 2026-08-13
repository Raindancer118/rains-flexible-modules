package de.raindancer.modules.mannequin.rules;

/**
 * Maps a hit's final damage to a 0–15 redstone comparator signal.
 *
 * <h2>Why plain linear scaling, rather than a curve</h2>
 * The owner's own worked example is damage around 16 producing roughly signal 12 — a hit that
 * would one-shot a bare player (20 damage) but not one in maxed netherite armor. A straight line
 * from {@code damage = 0 -> signal 0} to {@code damage = oneShotThreshold -> signal 15} already
 * lands there exactly: with the default threshold of 20.0, {@code 16 / 20 * 15 = 12.0}. A curve or
 * a breakpoint table would only be needed if the linear mapping missed that point, and it does not
 * — so the simplest thing that satisfies the one concrete data point given is what is shipped.
 * Anything above the threshold clamps at 15 rather than reporting a signal redstone cannot carry.
 */
public final class SignalStrengthRule implements IMannequinRule {

    public static final int MAX_SIGNAL = 15;

    /**
     * @param finalDamage     the hit's damage after everything else has been applied
     * @param oneShotThreshold the damage that maps to the maximum signal — {@code
     *                         MannequinSettings#oneShotThreshold}
     */
    public int signalFor(double finalDamage, double oneShotThreshold) {
        if (finalDamage <= 0.0 || oneShotThreshold <= 0.0) {
            return 0;
        }
        double scaled = (finalDamage / oneShotThreshold) * MAX_SIGNAL;
        int level = (int) Math.round(scaled);
        return Math.max(0, Math.min(MAX_SIGNAL, level));
    }

    @Override
    public String describe() {
        return "a hit's damage, scaled linearly against the one-shot threshold, into a 0-15 signal";
    }
}
