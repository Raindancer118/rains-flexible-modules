package de.raindancer.modules.essentials.rules;

/**
 * Whether a clear-sky or a dawn token does anything where somebody stands. When it would not, the token is
 * not spent: one used up on a sky that was already clear is one taken by a misclick.
 */
public final class SkyTokenRule {

    /** What came of asking. */
    public enum Outcome { GO, NO_SKY, ALREADY_CLEAR, ALREADY_DAY }

    /** The tick of the day the sun sets; from here until the next morning a night can be skipped. */
    public static final long SUNSET = 12_000L;

    private static final long DAY = 24_000L;

    /** @param hasSky whether the world has weather and a sun at all — the Nether and the End do not */
    public Outcome clearSky(boolean hasSky, boolean raining, boolean thundering) {
        if (!hasSky) {
            return Outcome.NO_SKY;
        }
        return raining || thundering ? Outcome.GO : Outcome.ALREADY_CLEAR;
    }

    /** @param timeOfDay ticks into the day, 0 at sunrise */
    public Outcome skipNight(boolean hasSky, long timeOfDay) {
        if (!hasSky) {
            return Outcome.NO_SKY;
        }
        return Math.floorMod(timeOfDay, DAY) >= SUNSET ? Outcome.GO : Outcome.ALREADY_DAY;
    }

    /** The next sunrise after {@code fullTime} — the world's own count of days goes on rather than back. */
    public long nextMorning(long fullTime) {
        return (Math.floorDiv(fullTime, DAY) + 1) * DAY;
    }
}
