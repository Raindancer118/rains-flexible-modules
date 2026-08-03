package de.raindancer.modules.api;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Chat;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Everything a module is given, and the only thing it is allowed to hold.
 *
 * <p>A module reaches the server through this and nowhere else. That is what makes the same code work as
 * a standalone plugin and as one feature of a larger one: {@link #plugin()} is whichever plugin happens
 * to be hosting it, {@link #dataFolder()} is wherever that host can spare, and the module never asked.
 *
 * <p>An interface, so a test can hand a module a fake and check what it did without a server. That is
 * not a nicety — a module that reached for {@code Bukkit} statics instead would only be testable by
 * booting Paper, and would therefore not be tested.
 */
public interface ModuleContext {

    /** The module this context belongs to. */
    ModuleInfo info();

    /**
     * The plugin hosting this module — for schedulers, for {@code registerEvents}, for anything Bukkit
     * wants a plugin for.
     *
     * <p><b>Not necessarily a plugin named after the module.</b> Hosted inside {@code RainsSMPCore} this
     * answers {@code RainsSMPCore}, so it is the wrong thing to put in a message to a player. Use
     * {@link #chat()} for that.
     */
    Plugin plugin();

    /** The shared foundation. Already enabled by the time a module sees this. */
    RainsCore core();

    /**
     * Where this module's files go — already created.
     *
     * <p>Its own data folder when it is a plugin of its own, and a corner of the host's when it is not.
     * See {@link ModuleLayout}.
     */
    Path dataFolder();

    /** This module's channel in the shared logfile, named after the module rather than the host. */
    LogChannel log();

    /** Chat signed with this module's own name, whoever is hosting it. */
    Chat chat();

    /** Whether this module is the plugin, or a guest in one. */
    boolean isStandalone();

    /** The name of the plugin hosting it — for the console line that says where it ended up. */
    String hostName();

    /**
     * Registers a listener, and remembers to unregister it.
     *
     * <p>Use this rather than {@code getPluginManager().registerEvents}: a module unregistered by the
     * host but whose listeners are still attached keeps answering events after it has stopped, which
     * looks like the module working and is nothing of the kind.
     */
    void listener(Listener listener);

    /** Something to close when the module stops — a database, a timer, a watcher. Reverse order. */
    void closeWith(AutoCloseable resource);

    /**
     * Binds this module's settings record to a {@code config.yml} of its own.
     *
     * <p>Its own file, not the host's: a module writing into the host's {@code config.yml} would fight
     * the host over the same keys, and moving the module to another plugin would lose the settings.
     */
    <T> SettingsStore<T> settings(Class<T> type, T defaults);

    /**
     * Another module in the same host, if it is there and running.
     *
     * <p>For a {@code wants} dependency, which is the only kind that can be absent. The answer is empty
     * rather than an exception precisely so that the caller has to decide what to do without it.
     */
    Optional<FlexModule> module(String id);
}
