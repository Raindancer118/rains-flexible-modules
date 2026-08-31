package de.raindancer.modules.manhunt.service;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A set of marks that each announce themselves once, the first time a falling value reaches them.
 *
 * <h2>One class for two very different things</h2>
 * "Five minutes left" and "the dragon is at half" are the same question asked twice: a number is
 * coming down, and certain points on the way are worth saying out loud exactly once. Writing that
 * twice would be two chances to get the once-only part wrong — which is the part that is easy to get
 * wrong, since the naive version fires on every tick the value spends below the mark.
 *
 * <h2>Why a jump announces only the lowest mark reached</h2>
 * A dragon hit for half its health at once has passed two marks. Saying both is noise about a moment
 * that already happened; saying the lowest is the truth about where things now stand. The marks flown
 * past are spent all the same, so nothing announces them later on the way further down.
 *
 * <h2>Never re-armed by a rising value</h2>
 * A clock does not go back up, but a dragon's health does — it heals off the end crystals. A mark that
 * re-armed every time would turn one dragon fight into a dozen identical announcements, so only
 * {@link #reset()}, which a fresh hunt calls, arms them again.
 */
public final class Thresholds {

    private final double[] marks;
    private final Set<Double> spent = ConcurrentHashMap.newKeySet();

    /** @param marks the points worth announcing, in any order */
    public Thresholds(double... marks) {
        double[] sorted = marks.clone();
        Arrays.sort(sorted);
        // Highest first, so the search below can stop at the first mark the value has reached.
        this.marks = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            this.marks[i] = sorted[sorted.length - 1 - i];
        }
    }

    /**
     * The mark {@code to} has just reached, if it reached one it had not already spent.
     *
     * @param from where the value was a moment ago
     * @param to   where it is now
     */
    public Optional<Double> crossed(double from, double to) {
        if (to >= from) {
            return Optional.empty();
        }
        Double reached = null;
        for (double mark : marks) {
            if (from > mark && to <= mark && spent.add(mark)) {
                // Keep going: a later mark in this list is lower, and the lowest one actually reached
                // is the one worth announcing. The ones passed on the way are spent by the add above.
                reached = mark;
            }
        }
        return Optional.ofNullable(reached);
    }

    /** Arms every mark again — a fresh hunt, or a fresh dragon. */
    public void reset() {
        spent.clear();
    }
}
