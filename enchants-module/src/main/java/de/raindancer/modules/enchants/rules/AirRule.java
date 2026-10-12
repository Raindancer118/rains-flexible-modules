package de.raindancer.modules.enchants.rules;

/** What Flight and Double Jump permit. Decides, and does nothing else. */
public final class AirRule {

    /** Blocks-per-tick of upward push at 100 %, a little over two blocks of height. */
    private static final double UP_AT_FULL_POWER = 0.75;

    public long capacityMillis(int flightLevel, int secondsPerLevel) {
        return Math.max(0, flightLevel) * Math.max(0, secondsPerLevel) * 1000L;
    }

    public int jumpsLeft(int jumpLevel, int used) {
        return Math.max(0, jumpLevel - Math.max(0, used));
    }

    public boolean mayFly(int flightLevel, long remainingMillis) {
        return flightLevel > 0 && remainingMillis > 0;
    }

    public boolean allowFlight(int flightLevel, long remainingMillis, int jumpLevel, int jumpsUsed) {
        return mayFly(flightLevel, remainingMillis) || jumpsLeft(jumpLevel, jumpsUsed) > 0;
    }

    public double jumpUp(int powerPercent) {
        return UP_AT_FULL_POWER * powerPercent / 100.0;
    }
}
