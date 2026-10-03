package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One run as a {@link SpeedrunMode} sees it: the session, the worlds it is played in, and a place to
 * hang whatever the mode needs for exactly as long as the run exists.
 *
 * <h2>Why listeners go through here</h2>
 * A mode that registers its own listeners has to remember to unregister them on every path a run can
 * end by — a finish, a forced reset, the last player leaving, a start that failed half way. The lobby
 * already knows every one of those paths, because it is the thing that forgets the run. So a listener
 * handed to {@link #listen} is unregistered there, once, whichever path it was.
 */
public final class SpeedrunRun {

    private static final LogChannel log = Log.of("speedrun");

    private final Plugin plugin;
    private final SpeedrunSession session;
    private final SpeedrunWorlds worlds;
    private final boolean resumed;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> onDisarm = new CopyOnWriteArrayList<>();
    private final SpeedrunSplitTracker splits;

    /**
     * Built by {@link SpeedrunLobby#start} and handed to the mode. Public so a mode's own tests can
     * build one without a lobby, which is the only way to test a mode at all from the other module.
     */
    public SpeedrunRun(Plugin plugin, SpeedrunSession session, SpeedrunWorlds worlds) {
        this(plugin, session, worlds, false);
    }

    public SpeedrunRun(Plugin plugin, SpeedrunSession session, SpeedrunWorlds worlds, boolean resumed) {
        this(plugin, session, worlds, resumed, null);
    }

    /** The lobby's own: the run shares the tracker the lobby splits, records and draws from. */
    SpeedrunRun(Plugin plugin, SpeedrunSession session, SpeedrunWorlds worlds, boolean resumed,
                SpeedrunSplitTracker splits) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.session = Objects.requireNonNull(session, "session");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.resumed = resumed;
        this.splits = splits == null ? new SpeedrunSplitTracker(session) : splits;
    }

    /**
     * A milestone of the mode's own — "First Runner caught" — to split at with {@link #split}. It is
     * listed after the built-in ones, kept in the run's history under its id, and compared against
     * the same milestone of earlier runs like any other.
     */
    public SpeedrunMilestone declareMilestone(String id, String label, Material icon) {
        return splits.declare(id, label, icon);
    }

    /**
     * {@code who} reached {@code milestoneId} — a built-in one or one {@link #declareMilestone declared}.
     * The first to reach it splits the run; it is announced, drawn and kept like every other split.
     *
     * @return whether this was the split
     */
    public boolean split(String milestoneId, UUID who) {
        return splits.reach(milestoneId, who);
    }

    /** Told about every split of this run as it happens — a mode's own reaction to one. */
    public void onSplit(Consumer<SpeedrunSplitTracker.Split> listener) {
        splits.onSplit(listener);
    }

    /**
     * Lines of the mode's own on every viewer's splits sidebar, asked per viewer each time it is
     * drawn — "Runners left: 2", "Nearest Hunter: 84 m".
     */
    public void hudLines(Function<UUID, List<Component>> lines) {
        splits.addHudLines(lines);
    }

    /** The run's splits — what is reached, what is next, how each compares. */
    public SpeedrunSplitTracker splits() {
        return splits;
    }

    /**
     * A run picked up after a restart ({@code /speedrunresume}): everybody is mid-game where they stand.
     * A mode skips whatever belongs to a fresh start — head starts, hand-outs of once-only items.
     */
    public boolean resumed() {
        return resumed;
    }

    public SpeedrunSession session() {
        return session;
    }

    /** Everybody racing — those it started with, plus anybody a mode added since. */
    public Set<UUID> participants() {
        return session.participants();
    }

    public Plugin plugin() {
        return plugin;
    }

    /** The run's overworld — the lobby world. */
    public String overworld() {
        return worlds.overworld();
    }

    /** Whether {@code worldName} is one of the run's three worlds. */
    public boolean isRunWorld(String worldName) {
        return worlds.contains(worldName);
    }

    /** Registers {@code listener} now and unregisters it when the lobby forgets this run. */
    public void listen(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    /** Runs {@code cleanup} once, when the lobby forgets this run — however it came to be forgotten. */
    public void onDisarm(Runnable cleanup) {
        if (cleanup != null) {
            onDisarm.add(cleanup);
        }
    }

    /**
     * Called by the lobby, exactly once, when it forgets this run. Public for the same reason the
     * constructor is: a mode's own tests have to be able to reach the moment its cleanup runs.
     */
    public void disarm() {
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        for (Runnable cleanup : onDisarm) {
            try {
                cleanup.run();
            } catch (RuntimeException broken) {
                log.error(broken, "A game mode's cleanup threw while its run was being forgotten.");
            }
        }
        onDisarm.clear();
    }
}
