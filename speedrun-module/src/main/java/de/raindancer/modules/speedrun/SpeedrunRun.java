package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> onDisarm = new CopyOnWriteArrayList<>();

    /**
     * Built by {@link SpeedrunLobby#start} and handed to the mode. Public so a mode's own tests can
     * build one without a lobby, which is the only way to test a mode at all from the other module.
     */
    public SpeedrunRun(Plugin plugin, SpeedrunSession session, SpeedrunWorlds worlds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.session = Objects.requireNonNull(session, "session");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    public SpeedrunSession session() {
        return session;
    }

    /** Everybody racing, fixed when the run started. */
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
