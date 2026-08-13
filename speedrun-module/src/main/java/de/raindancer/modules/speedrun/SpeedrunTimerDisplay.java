package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.UUID;

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
final class SpeedrunTimerDisplay {

    static final String OWNER = "speedrun-timer";

    /** Runs {@code task} once a second until told to stop — the seam a test drives by hand instead of
     *  waiting on a real Paper scheduler; see {@code GameTimerService.RoundTicker} for the same shape. */
    @FunctionalInterface
    interface Ticker {
        AutoCloseable everySecond(Runnable task);
    }

    private final ActionBars actionBars;
    private final Ticker ticker;

    private AutoCloseable running;

    SpeedrunTimerDisplay(ActionBars actionBars, Ticker ticker) {
        this.actionBars = actionBars;
        this.ticker = ticker;
    }

    /** The real ticker: onto Core's own repeating scheduler, once a second. */
    static Ticker viaScheduling(Plugin plugin) {
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
    void start(SpeedrunSession session) {
        stop();
        show(session);
        running = ticker.everySecond(() -> show(session));
        session.onFinish(outcome -> stop(session));
    }

    private void show(SpeedrunSession session) {
        Component text = format(session.elapsed());
        for (UUID participant : session.participants()) {
            actionBars.show(participant, OWNER, text, ActionBars.UNTIL_CLEARED, ActionBarPriority.LOW);
        }
    }

    /** Cancels the tick and takes the clock off every participant's bar — called once a run finishes. */
    private void stop(SpeedrunSession session) {
        stop();
        for (UUID participant : session.participants()) {
            actionBars.clear(participant, OWNER);
        }
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

    static Component format(Duration elapsed) {
        long seconds = Math.max(0, elapsed.getSeconds());
        return Component.text("%d:%02d".formatted(seconds / 60, seconds % 60), NamedTextColor.YELLOW);
    }
}
