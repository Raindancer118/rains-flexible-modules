package de.raindancer.modules.api;

import org.bukkit.plugin.Plugin;

/**
 * Whatever plugin is carrying the modules.
 *
 * <p>Three implementations are expected and they are all trivial: the standard wrapper, when the module
 * is its own plugin; a plugin like {@code RainsSMPCore} that hosts several; and a test. The point of the
 * interface is that no module ever has to know which of the three it landed in.
 */
public interface ModuleHost {

    /** What to call the host in a log line — usually the plugin's name. */
    String name();

    /** The plugin Bukkit knows about: listeners are registered against it, tasks scheduled on it. */
    Plugin plugin();

    /** Where this host puts module files. */
    ModuleLayout layout();

    /** The registry this host is running, so a module can ask after another one. */
    ModuleRegistry registry();

    /** Whether a module here is the plugin, rather than a guest in one. */
    default boolean isStandalone() {
        return !layout().isShared();
    }
}
