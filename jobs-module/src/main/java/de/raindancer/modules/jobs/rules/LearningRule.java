package de.raindancer.modules.jobs.rules;

/**
 * How big the next goal of a kind should be, from how the last one went: aimed so that, at the pace players
 * just managed, it is reached three quarters of the way through its time. A goal hit on day one doubles; one
 * missed shrinks to what was managed. Never more than twice or less than half per round, so one odd week
 * (a holiday, a new player who fishes all night) does not swing it wildly.
 */
public final class LearningRule implements IJobsRule {

    /** Where in a goal's time it should be reached. */
    static final double AIM = 0.75;
    /** The shortest a goal counts as having taken — a lucky hand-in in the first minute is not a pace. */
    static final long SHORTEST = 3_600_000L;

    /**
     * @param amount    what the goal asked for
     * @param delivered what players gave towards it
     * @param finished  when it was reached, or when its time ran out
     * @param duration  how long it was meant to run
     */
    public int next(int amount, int delivered, long started, long finished, long duration, int least, int most) {
        long elapsed = Math.max(SHORTEST, finished - started);
        double pace = Math.max(0, delivered) / (double) elapsed;
        double ideal = pace * duration * AIM;
        double held = Math.clamp(ideal, amount * 0.5, amount * 2.0);
        long rounded = Math.round(held);
        return (int) Math.clamp(rounded, Math.max(1, least), Math.max(Math.max(1, least), most));
    }

    @Override
    public String describe() {
        return "how big the next goal of a kind should be, from the pace of the last";
    }
}
