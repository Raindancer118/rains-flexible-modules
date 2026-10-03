package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One live speedrun: a roster, a clock, and whatever is watching for the moment it ends.
 *
 * <h2>The one thing that has to be right</h2>
 * A run finishes <em>once</em>. Two advancement events for the same run arriving a millisecond apart
 * — plausible when two participants complete the same trigger together — must not stop the timer
 * twice or announce the finish twice. Exactly the same problem as {@code Achievements.award}, and
 * solved the same way: the outcome is written with one atomic compare-and-set, and only the caller
 * that wins it does anything.
 */
public final class SpeedrunSession {

    private static final LogChannel log = Log.of("speedrun");

    private final Set<UUID> participants;
    private final SpeedrunTimer timer;
    private final List<Consumer<SpeedrunOutcome>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<UUID>> removalListeners = new CopyOnWriteArrayList<>();
    /** Copy-on-write: {@link #finish} walks it unlocked while a goal removal may change it from another
     *  thread, and a list that threw there would leave a finished run nobody was told about. */
    private final List<SpeedrunEndCondition> conditions = new CopyOnWriteArrayList<>();

    private volatile SpeedrunState state = SpeedrunState.NOT_STARTED;
    /** Set exactly once, by whichever {@link #finish} call wins the race — see the class javadoc. */
    private final AtomicReference<SpeedrunOutcome> outcome = new AtomicReference<>();

    public SpeedrunSession(Set<UUID> participants) {
        this(participants, new SpeedrunTimer());
    }

    public SpeedrunSession(Set<UUID> participants, SpeedrunTimer timer) {
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("A speedrun needs at least one participant.");
        }
        this.participants = ConcurrentHashMap.newKeySet();
        this.participants.addAll(participants);
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    // ---------------------------------------------------------------------------- lifecycle

    /**
     * Starts the run. A no-op if it has already been started — a second call (a double-fired command,
     * say) does not restart the clock out from under a run in progress.
     */
    public synchronized void start() {
        start(Duration.ZERO);
    }

    /** Starts with {@code already} on the clock — a run resumed after a restart. */
    public synchronized void start(Duration already) {
        if (state != SpeedrunState.NOT_STARTED) {
            return;
        }
        timer.start(already);
        state = SpeedrunState.RUNNING;
    }

    /** {@code /speedruntime}: the clock set by hand. Ignored once finished — the result stands. */
    public synchronized boolean setElapsed(Duration reading) {
        if (state == SpeedrunState.FINISHED || state == SpeedrunState.NOT_STARTED) {
            return false;
        }
        timer.set(reading);
        return true;
    }

    /**
     * Stops the roster from being empty-handed: the last participant logged out. A no-op unless the
     * run is currently {@code RUNNING} — pausing a run that has not started, is already paused, or is
     * over would either do nothing useful or, for a finished run, resurrect a clock nobody should be
     * reading any more.
     */
    synchronized void pauseForEmptyRoster() {
        if (state != SpeedrunState.RUNNING) {
            return;
        }
        timer.pause();
        state = SpeedrunState.PAUSED;
    }

    /**
     * Somebody is back. A no-op unless the run is currently {@code PAUSED}.
     */
    synchronized void resume() {
        if (state != SpeedrunState.PAUSED) {
            return;
        }
        timer.resume();
        state = SpeedrunState.RUNNING;
    }

    /**
     * Ends the run, if it has not already ended. Safe to call more than once, from more than one
     * thread, and from inside a {@link SpeedrunEndCondition} that fires after another condition has
     * already finished it — see the class javadoc.
     *
     * @param reason what ended it, e.g. {@code "advancement:minecraft:end/kill_dragon"}
     */
    public void finish(String reason) {
        timer.stop();
        SpeedrunOutcome candidate = new SpeedrunOutcome(reason, timer.elapsed(), Instant.now());
        // One atomic step: whichever caller's compareAndSet lands first is the one whose outcome
        // sticks, and every other concurrent (or later) call sees a non-null reference and does
        // nothing further. Checking state first and writing second is exactly the gap that lets two
        // callers both believe they were the one that finished it.
        if (!outcome.compareAndSet(null, candidate)) {
            return;
        }
        state = SpeedrunState.FINISHED;
        for (SpeedrunEndCondition condition : conditions) {
            try {
                condition.disarm();
            } catch (RuntimeException broken) {
                // One condition failing to unregister must not stop the others, nor undo the finish.
                log.error(broken, "'{}' could not be disarmed after the run finished.",
                        condition.describe());
            }
        }
        announce(candidate);
    }

    private void announce(SpeedrunOutcome result) {
        for (Consumer<SpeedrunOutcome> listener : listeners) {
            try {
                listener.accept(result);
            } catch (RuntimeException broken) {
                // Same reasoning as Achievements.announce: one listener's bug is not everybody's.
                log.error(broken, "A speedrun finish listener threw for '{}'.", result.reason());
            }
        }
    }

    // ---------------------------------------------------------------------------- watching

    /** Called once, with the outcome, the moment the run finishes — never more than once per session. */
    public void onFinish(Consumer<SpeedrunOutcome> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Arms {@code condition} immediately and keeps it, so {@link #finish} can disarm it later. */
    public synchronized void addEndCondition(SpeedrunEndCondition condition) {
        Objects.requireNonNull(condition, "condition");
        conditions.add(condition);
        condition.arm(this);
    }

    /** Disarms and drops every condition {@code which} matches — a goal removed mid-run. */
    public synchronized int removeEndConditions(Predicate<SpeedrunEndCondition> which) {
        int removed = 0;
        for (SpeedrunEndCondition condition : conditions) {
            if (which.test(condition) && conditions.remove(condition)) {
                condition.disarm();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Takes {@code player} off the live roster mid-run — Manhunt's {@code leave}/{@code unassign}.
     * False if they were not racing, the run is finished (the result's roster stands), or they are
     * the last one: a run with nobody racing could never end — no participant left to reach the goal
     * or die — and would pause on every stranger's quit without ever resuming.
     *
     * <p>Every armed condition is told, so one judged over the whole roster (everybody dead) can end
     * the run now that the roster is smaller.
     */
    public boolean removeParticipant(UUID player) {
        synchronized (this) {
            if (player == null || state == SpeedrunState.FINISHED || participants.size() <= 1
                    || !participants.remove(player)) {
                return false;
            }
        }
        // Outside the lock: a condition may finish the run from here, and finish() calls out to
        // listeners that must not run under this session's monitor.
        for (SpeedrunEndCondition condition : conditions) {
            try {
                condition.participantRemoved(player);
            } catch (RuntimeException broken) {
                log.error(broken, "'{}' failed on a participant being removed.", condition.describe());
            }
        }
        for (Consumer<UUID> listener : removalListeners) {
            try {
                listener.accept(player);
            } catch (RuntimeException broken) {
                log.error(broken, "A speedrun roster listener threw for a removal.");
            }
        }
        return true;
    }

    /** Told after {@link #removeParticipant} took somebody off — {@link SpeedrunOccupancyListener}'s hook. */
    void onParticipantRemoved(Consumer<UUID> listener) {
        if (listener != null) {
            removalListeners.add(listener);
        }
    }

    /** A latecomer added mid-run (Manhunt's {@code /manhunt assign}). False if already racing. */
    public boolean addParticipant(UUID player) {
        return state != SpeedrunState.FINISHED && participants.add(player);
    }

    // ---------------------------------------------------------------------------- reading

    /** Unmodifiable view of who is running this. */
    public Set<UUID> participants() {
        return Collections.unmodifiableSet(participants);
    }

    public SpeedrunState state() {
        return state;
    }

    /** Correct mid-run and across pause/resume — see {@link SpeedrunTimer#elapsed()}. */
    public Duration elapsed() {
        return timer.elapsed();
    }

    /** The outcome, once {@link #finish} has been called; empty until then. */
    public Optional<SpeedrunOutcome> outcome() {
        return Optional.ofNullable(outcome.get());
    }
}
