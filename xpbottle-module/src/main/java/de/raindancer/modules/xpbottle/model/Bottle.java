package de.raindancer.modules.xpbottle.model;

/**
 * One bottle, as it stands right now: what tier it is, how many experience points are in it and how
 * many it could hold.
 *
 * <h2>Why points and not levels</h2>
 * A level is worth a different number of points depending on which level it is — the seventh costs
 * 16 and the thirty-seventh costs 100. Anything that stores "three levels" therefore gives back a
 * different amount than it took, in whichever direction the player has moved since, and that is a
 * duplication bug in one direction and a theft bug in the other. So the unit here is the point, all
 * the way through, and levels only ever appear in what a player is <em>shown</em>.
 *
 * <h2>Why the capacity is carried rather than looked up</h2>
 * Capacity comes from the settings, which an owner can change while bottles are already sitting in
 * chests. A bottle holding more than its tier now allows is therefore a real state, and one this
 * record can describe: {@link #room()} is zero rather than negative, {@link #isFull()} is true, and
 * nothing anywhere subtracts its way into giving somebody experience out of nowhere.
 *
 * @param level  0 for a plain glass bottle, 1 and up for a siphon bottle of that tier
 * @param stored experience points currently in it
 * @param capacity the most it may hold, from the settings in force when it was read
 */
public record Bottle(int level, int stored, int capacity) {

    /** A plain glass bottle nobody has put anything in yet. */
    public static Bottle empty(int capacity) {
        return new Bottle(0, 0, capacity);
    }

    public Bottle {
        level = Math.max(0, level);
        stored = Math.max(0, stored);
        capacity = Math.max(0, capacity);
    }

    /** An ordinary glass bottle: it draws from the holder's own experience and nothing else. */
    public boolean isPlain() {
        return level == 0;
    }

    /**
     * Whether this one can pull loose experience off the ground.
     *
     * <p>The whole difference the enchantment makes, beside holding more. A plain bottle answers no,
     * which is what makes "hold it down and nothing happens" a decision rather than a bug.
     */
    public boolean mayVacuum() {
        return level >= 1;
    }

    /** Whether there is anything in it to pour back. */
    public boolean isEmpty() {
        return stored <= 0;
    }

    public boolean isFull() {
        return stored >= capacity;
    }

    /** How many more points would go in. Never negative, however the capacity has since changed. */
    public int room() {
        return Math.max(0, capacity - stored);
    }

    /** The same bottle holding that many points instead. */
    public Bottle holding(int points) {
        return new Bottle(level, points, capacity);
    }

    /** The same bottle with that many more points in it, never past what it can hold. */
    public Bottle plus(int points) {
        return holding(stored + Math.max(0, Math.min(points, room())));
    }

    /** The same bottle, emptied — what pouring one out leaves behind. */
    public Bottle poured() {
        return holding(0);
    }
}
