package de.raindancer.modules.enchants.model;

/** What the air enchantments remember about one player between ticks. */
public final class AirState {

    public FlightBudget budget = new FlightBudget();
    /** Mid-air jumps used since last touching the ground. */
    public int jumpsUsed;
    /** Whether the player's permission to fly is ours to take back. */
    public boolean granted;
    public long lastNanos = System.nanoTime();
    /** Until when a just-used jump keeps flight switched off, so the client resets its double-tap. */
    public long holdUntilNanos;
    /** Until when the ground does not count: the client has not yet obeyed the push of a jump. */
    public long airborneUntilNanos;
    /** Lifetime flight, millis; whole minutes of it are the Frequent Flyer steps. */
    public long flownMillis;
}
