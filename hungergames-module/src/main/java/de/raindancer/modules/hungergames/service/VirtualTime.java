package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The clock a round actually runs on, which is not quite the wall clock.
 *
 * <h2>Why a round needs its own clock at all</h2>
 * {@code /speedup} lets a gamemaster fast-forward a round that is dragging — a lobby nobody is filling, a
 * border phase nobody is racing — without touching the server's real clock, which every other plugin and
 * every log timestamp on the server still has to agree with. So instead of asking the wall clock and
 * multiplying afterwards (which forgets the multiplier the moment it changes), this accumulates virtual
 * milliseconds on every {@link #tick()}, each slice already scaled by whatever the multiplier was
 * <em>during that slice</em>. A round sped up for its last five minutes then has a log that agrees with
 * itself about how the first fifty took as long as they visibly did.
 *
 * <h2>Why the wall clock is a {@link LongSupplier} rather than {@code System.currentTimeMillis()}</h2>
 * A test that wants to assert "eight seconds elapsed after the multiplier changed" cannot wait eight real
 * seconds for the system clock to agree, so every read here goes through an injected supplier — production
 * code hands in {@code System::currentTimeMillis} and a test hands in a counter it moves by hand. See
 * {@code VirtualTimeTest}.
 */
public final class VirtualTime implements IHungerGamesService {

    private final LongSupplier wallClock;

    private double multiplier = 1.0;
    private long virtualElapsedMs;
    private long lastRealTime;
    private boolean running;

    public VirtualTime() {
        this(System::currentTimeMillis);
    }

    public VirtualTime(LongSupplier wallClock) {
        this.wallClock = wallClock;
    }

    /**
     * Nothing here comes from {@code config.yml}: the round length a settings screen offers is read
     * separately by whoever asks {@link #elapsed()} to compare against it, and the multiplier is round-local
     * state a gamemaster sets live, never a default. Implemented empty rather than left off the class, so a
     * later settings key does not slip in unnoticed — see {@code IHungerGamesService}'s class note.
     */
    @Override
    public void settings(HungerGamesSettings settings) {
        // intentionally empty — see class javadoc
    }

    /** Starts the measurement at zero. Called when a round moves to {@code RUNNING}. */
    public void start() {
        running = true;
        virtualElapsedMs = 0;
        lastRealTime = wallClock.getAsLong();
    }

    /** Continues the measurement after a restore, from a virtual offset read out of a saved snapshot. */
    public void resumeAt(Duration elapsed) {
        running = true;
        virtualElapsedMs = elapsed.toMillis();
        lastRealTime = wallClock.getAsLong();
    }

    /** Accumulates whatever is owed up to now, then stops accepting further time. */
    public void stop() {
        tick();
        running = false;
    }

    /** Folds in the wall-clock time since the last tick, scaled by the multiplier in effect during it. */
    public void tick() {
        if (!running) {
            return;
        }
        long now = wallClock.getAsLong();
        virtualElapsedMs += (long) ((now - lastRealTime) * multiplier);
        lastRealTime = now;
    }

    /** The round's own elapsed time. Ticks first, so a read is never stale by more than this call. */
    public Duration elapsed() {
        tick();
        return Duration.ofMillis(virtualElapsedMs);
    }

    public boolean isRunning() {
        return running;
    }

    public double multiplier() {
        return multiplier;
    }

    /**
     * Changes the pace from here on.
     *
     * @param multiplier clamped up to {@code 1.0} — a round cannot be told to run in slow motion, because
     *                   nothing downstream (grace period, border phases, the disconnect-elimination
     *                   timeout) was ever designed to be asked about that, and a round that never finishes
     *                   is a worse failure than one an admin merely forgot to speed up
     */
    public void setMultiplier(double multiplier) {
        tick(); // whatever has already elapsed is settled at the old pace before the new one applies
        this.multiplier = Math.max(1.0, multiplier);
    }
}
