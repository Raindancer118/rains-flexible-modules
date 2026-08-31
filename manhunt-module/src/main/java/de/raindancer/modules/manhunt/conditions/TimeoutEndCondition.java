package de.raindancer.modules.manhunt.conditions;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Objects;

/**
 * Ends the run for the Hunters — {@link ManhuntSettings.HunterWinCondition#TIMEOUT} — once
 * {@code limit} has passed since the run started, provided nothing else finished it first.
 *
 * <p>A global-region one-shot timer, not tied to any single player or world: the clock has to keep
 * running even if every Hunter has momentarily disconnected, which is exactly the state
 * {@code SpeedrunOccupancyListener} pauses the session's own elapsed-time clock for. The two are
 * independent on purpose — a Hunter party that logs off to wait out the clock does not get free
 * minutes back for it.
 */
public final class TimeoutEndCondition implements SpeedrunEndCondition {

    private final Plugin plugin;
    private final Duration limit;
    private ScheduledTask task;

    public TimeoutEndCondition(Plugin plugin, Duration limit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.limit = Objects.requireNonNull(limit, "limit");
        if (limit.isNegative() || limit.isZero()) {
            throw new IllegalArgumentException("a timeout has to be a positive duration");
        }
    }

    @Override
    public void arm(SpeedrunSession session) {
        Objects.requireNonNull(session, "session");
        long delayTicks = Math.max(1L, limit.toMillis() / 50L);
        // A one-shot fired through the repeating-timer call, cancelling itself the moment it runs —
        // Scheduling has no bare "run once, later, cancellably" wrapper, and this needs to be
        // cancellable so a run that finishes some other way first (session.finish is a no-op the
        // second time either way, but a live timer still ticking after the world has reset is a task
        // referencing a world that is gone) can be torn down cleanly by disarm().
        task = Scheduling.globalTimer(plugin, delayTicks, delayTicks, self -> {
            self.cancel();
            session.finish("timeout");
        });
    }

    @Override
    public void disarm() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public String describe() {
        return "timeout:" + limit.toMinutes() + "m";
    }
}
