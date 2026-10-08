package de.raindancer.modules.economy.rules;

/**
 * Crash: a multiplier climbs from 1× until it crashes. The crash point is drawn so that the chance of it
 * reaching any {@code m} is exactly {@code (1 − edge) / m} — so cashing out at any target returns exactly
 * one minus the house edge, and a crash at 1.00× happens with chance {@code edge}.
 */
public final class CrashRule implements IEconomyRule {

    /** How fast it climbs: doubles about every eleven and a half seconds. */
    public static final double GROWTH_PER_MILLI = Math.log(2) / 11_500.0;

    /**
     * The highest a round goes — about two minutes of climbing. Above it the odds would be exact only for
     * rounds nobody waits for; the cap costs a player cashing out below it nothing.
     */
    public static final double MOST = 1_000.0;

    /** @param uniform a number in (0, 1] */
    public double crashPoint(double uniform, double edge) {
        double u = Math.max(1e-9, Math.min(1, uniform));
        double point = (1.0 - Math.max(0, Math.min(0.5, edge))) / u;
        return Math.min(MOST, Math.max(1.0, Math.floor(point * 100) / 100.0));
    }

    public double multiplierAt(long millis) {
        return Math.floor(Math.exp(Math.max(0, millis) * GROWTH_PER_MILLI) * 100) / 100.0;
    }

    /**
     * Whether an automatic cash-out is paid: the round reached it, even if the same step also crashed.
     * Reaching means the crash point is at least the target, the same sense the fair odds are drawn in.
     */
    public boolean autoCashesOut(double target, double climbed, double crashPoint) {
        return target > 1 && target <= climbed && target <= crashPoint;
    }

    @Override
    public String describe() {
        return "where a crash round crashes, and how fast its multiplier climbs";
    }
}
