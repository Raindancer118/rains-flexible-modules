package de.raindancer.modules.api;

/**
 * The one registry a host and its bootstrapper share.
 *
 * <p>Static because Paper leaves no alternative. Commands must be registered from a
 * {@code PluginBootstrap}, which runs before the plugin object exists and cannot be handed anything by
 * it — and the bootstrapper and {@code onEnable} have to be talking about the same modules, or a command
 * ends up wired to a module the host never enabled.
 *
 * <p>Static per <em>classloader</em>, which is what keeps it safe. Paper gives every plugin its own, so
 * two plugins built on this each get their own registry and neither can see the other's modules. That is
 * also why {@link #shutdown()} exists: a reload replaces the classloader, but a host that is disabled
 * without being unloaded would otherwise leave its modules marked as running.
 */
public final class Modules {

    private static ModuleRegistry registry = new ModuleRegistry();
    private static boolean discovered;

    private Modules() {
    }

    /** This host's registry. */
    public static synchronized ModuleRegistry registry() {
        return registry;
    }

    /**
     * Fills the registry from {@code META-INF/services}, once.
     *
     * <p>Idempotent on purpose: the bootstrapper calls it to find the commands and {@code onEnable} calls
     * it in case there was no bootstrapper. A second discovery that added the modules again would give
     * every id a duplicate and every command a collision.
     *
     * <p>Idempotent only once something was <em>found</em>, though. The two calls happen in different
     * phases with different classpaths: Paper runs bootstrap with its own dependency tree, so a host whose
     * dependency is declared for the server phase alone has none of that dependency's classes yet when the
     * bootstrapper looks. Every module then fails to link, and latching on that would hand the call in
     * {@code onEnable} — where the classes are there — the cached nothing. That shipped once, and the log
     * said the jar contained no modules about a jar that contained one.
     *
     * <p>So nothing found is not an answer worth keeping. Looking twice when there is genuinely nothing to
     * find costs a service-file scan of an empty classpath, once per start.
     */
    public static synchronized void discover(ClassLoader loader) {
        if (discovered) {
            return;
        }
        ModuleDiscovery.Discovered found = ModuleDiscovery.onClasspath(loader);
        found.problems().forEach(registry::problem);
        registry.addAll(found.modules());
        discovered = !found.modules().isEmpty();
    }

    /** Throws the registry away. For tests, and for the second half of {@link #shutdown()}. */
    public static synchronized void reset() {
        registry = new ModuleRegistry();
        discovered = false;
    }

    /** Stops everything and forgets it — what a host calls from {@code onDisable}. */
    public static synchronized void shutdown() {
        registry.disableAll();
        reset();
    }
}
