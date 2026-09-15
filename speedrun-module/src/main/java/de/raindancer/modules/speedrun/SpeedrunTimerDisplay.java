package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The running clock on every participant's action bar — {@code m:ss}, counting up from zero.
 *
 * <h2>Why the action bar, and why this ticks its own second</h2>
 * Asked for over the boss bar: a boss bar is a wide, hard-to-miss banner, and a run that already
 * fills the screen with the destination and the hostiles in front of it does not need one more.
 * {@link ActionBars#show} redraws whatever {@link Component} it is handed, but it does not know that
 * component is secretly a function of time — {@code "1:04"} does not turn into {@code "1:05"} on its
 * own. So this class is the thing that knows a second has passed, and calls {@code show} again with a
 * freshly formatted string; {@link ActionBars} still owns the slot itself, arbitrating against
 * whatever else — a claim notice, a home confirmation — momentarily wants the same player's bar.
 *
 * <h2>Why one instance per lobby, not per run</h2>
 * There is nothing here that outlives a single run: {@link #start} always {@link #stop}s whatever
 * came before it, the same way {@code SpeedrunLobby} only ever has one live session. A fresh instance
 * per run would be a second thing to remember to throw away.
 */
public final class SpeedrunTimerDisplay {

    /**
     * The action bar slot this display owns, when the caller does not name one.
     *
     * <p>Named per caller rather than shared, because the slot is arbitrated by owner: two clocks
     * sharing one owner would take turns overwriting each other on the same player's bar. manhunt-
     * module runs its own hunt clock through this class and passes its own.
     */
    public static final String OWNER = "speedrun-timer";

    /** Runs {@code task} once a second until told to stop — the seam a test drives by hand instead of
     *  waiting on a real Paper scheduler; see {@code GameTimerService.RoundTicker} for the same shape. */
    @FunctionalInterface
    public interface Ticker {
        AutoCloseable everySecond(Runnable task);
    }

    private final ActionBars actionBars;
    private final Ticker ticker;
    private final String owner;

    /** Who else is shown the clock, asked again on every tick — see {@link #alsoShowTo}. */
    private volatile Supplier<Collection<UUID>> onlookers = Set::of;
    /** Exactly whose bar the clock is on right now, so it can be taken off again — the audience is
     *  not a fixed roster, so "everybody who was ever shown it" is the only safe thing to clear. */
    private final Set<UUID> showing = ConcurrentHashMap.newKeySet();

    private AutoCloseable running;

    public SpeedrunTimerDisplay(ActionBars actionBars, Ticker ticker) {
        this(actionBars, ticker, OWNER);
    }

    /** The same, showing the clock in {@code owner}'s own action bar slot — see {@link #OWNER}. */
    public SpeedrunTimerDisplay(ActionBars actionBars, Ticker ticker, String owner) {
        this.actionBars = actionBars;
        this.ticker = ticker;
        this.owner = owner == null || owner.isBlank() ? OWNER : owner;
    }

    /**
     * Also shows the clock to whoever {@code onlookers} names at the moment of each tick — somebody
     * who walked into the lobby world after the run began, typically, who is not a participant and
     * never will be for this run.
     *
     * <p>A supplier rather than a set, because the whole point is the people who were not there when
     * {@link #start} was called: a set handed in once would be the same roster the session already
     * has. Re-asked every second, so somebody arriving mid-run waits at most that long, and somebody
     * leaving has the clock taken off their bar on the next tick.
     */
    public void alsoShowTo(Supplier<Collection<UUID>> onlookers) {
        this.onlookers = onlookers == null ? Set::of : onlookers;
    }

    /** The real ticker: onto Core's own repeating scheduler, once a second. */
    public static Ticker viaScheduling(Plugin plugin) {
        return task -> {
            var scheduled = Scheduling.globalTimer(plugin, 20L, 20L, handle -> task.run());
            return scheduled::cancel;
        };
    }

    /**
     * Starts showing {@code session}'s clock to every one of its participants, and arranges for
     * {@link #stop} to be called the moment it finishes — a caller does not have to remember to clean
     * up after a run that ends on its own.
     */
    public void start(SpeedrunSession session) {
        stop();
        show(session);
        running = ticker.everySecond(() -> show(session));
        session.onFinish(outcome -> stop(session));
    }

    private void show(SpeedrunSession session) {
        Component text = format(session.elapsed());
        Set<UUID> audience = new HashSet<>(session.participants());
        audience.addAll(onlookers.get());
        for (UUID viewer : audience) {
            actionBars.show(viewer, owner, text, ActionBars.UNTIL_CLEARED, ActionBarPriority.LOW);
        }
        // Whoever was watching a second ago and is not in the audience now — they walked out of the
        // lobby world — gets their own bar back rather than a clock frozen at the moment they left.
        for (UUID gone : showing) {
            if (!audience.contains(gone)) {
                actionBars.clear(gone, owner);
            }
        }
        showing.clear();
        showing.addAll(audience);
    }

    /** Cancels the tick and takes the clock off every bar it is on — called once a run finishes. */
    private void stop(SpeedrunSession session) {
        stop();
        Set<UUID> audience = new HashSet<>(showing);
        audience.addAll(session.participants());
        for (UUID viewer : audience) {
            actionBars.clear(viewer, owner);
        }
        showing.clear();
    }

    private void stop() {
        if (running == null) {
            return;
        }
        try {
            running.close();
        } catch (Exception ignored) {
            // A cancel that fails leaves nothing dangerous behind — the task simply outlives the run
            // by at most one more second, and finds the slot already cleared when it fires.
        }
        running = null;
    }

    public static Component format(Duration elapsed) {
        long seconds = Math.max(0, elapsed.getSeconds());
        return Component.text("%d:%02d".formatted(seconds / 60, seconds % 60), NamedTextColor.YELLOW);
    }
}
