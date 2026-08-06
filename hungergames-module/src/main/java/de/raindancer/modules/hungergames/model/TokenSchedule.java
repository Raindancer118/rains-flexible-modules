package de.raindancer.modules.hungergames.model;

import java.time.Duration;

/**
 * The pure arithmetic behind sponsor tokens' time-based grant: how many grant waves are due at a given
 * game time, and how many tokens a particular player is still owed.
 *
 * <h2>The shape of a wave</h2>
 * Wave one falls at {@code firstAfter}; every wave after that is a further {@code interval} apart. That is
 * the whole schedule — a straight line of equally-spaced points, unlike {@link Schedule}'s arbitrary
 * timetable, because sponsor tokens are meant to trickle in steadily rather than land at a handful of
 * story beats.
 *
 * <h2>Why the caller tracks waves per player rather than tokens per player</h2>
 * Because "how many waves has this player already been paid for" is what stays correct across a rejoin
 * and a server restart. A player who was offline for wave three and four rejoins owed both, in one grant
 * rather than two separate top-ups nobody remembers were partial; a player who reconnects a second time
 * after already being paid for everything due is owed nothing, because the wave count they hold has not
 * moved. Tracking tokens directly would need a second number just to answer "was this the fourth wave or
 * a manual grant", and the two numbers would eventually disagree.
 */
public final class TokenSchedule {

    private TokenSchedule() {
    }

    /**
     * @param elapsed    the current (virtual) game time
     * @param firstAfter game time until the first wave
     * @param interval   the gap between later waves (must be positive to have more than one wave)
     * @return how many grant waves are due by now
     */
    public static int dueWaves(Duration elapsed, Duration firstAfter, Duration interval) {
        if (elapsed.compareTo(firstAfter) < 0) {
            return 0;
        }
        // Measured in milliseconds, so an interval under a millisecond is a positive Duration whose
        // toMillis() is zero — isZero() is false and the division below is by nothing. Reachable only from
        // code rather than from a config file, which measures the interval in seconds, but this is a public
        // pure function and a test or a host may hand it anything.
        long intervalMillis = interval.toMillis();
        if (intervalMillis <= 0) {
            return 1;
        }
        long sinceFirst = elapsed.minus(firstAfter).toMillis();
        return 1 + (int) (sinceFirst / intervalMillis);
    }

    /**
     * Tokens a player is still owed, respecting the optional cap on tokens earned per player.
     *
     * @param dueWaves          waves due by now, per {@link #dueWaves}
     * @param receivedWaves     waves this player has already been paid for
     * @param amountPerInterval tokens granted per wave
     * @param earnedTotal       tokens this player has earned in total so far
     * @param maxPerPlayer      the cap on tokens earned ({@code 0} means unlimited)
     */
    public static int pendingTokens(int dueWaves, int receivedWaves, int amountPerInterval,
                                     int earnedTotal, int maxPerPlayer) {
        int missingWaves = Math.max(0, dueWaves - receivedWaves);
        // Multiplied as longs and clamped back. As ints, a long round with a short interval and a generous
        // grant overflows to a negative number, and with no cap set that negative travels out of here as the
        // amount somebody is owed — which is a sponsor payout that takes tokens away.
        long amount = Math.max(0L, (long) missingWaves * Math.max(0, amountPerInterval));
        if (maxPerPlayer > 0) {
            amount = Math.min(amount, Math.max(0, maxPerPlayer - earnedTotal));
        }
        return (int) Math.min(amount, Integer.MAX_VALUE);
    }
}
