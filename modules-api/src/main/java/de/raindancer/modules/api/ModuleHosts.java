package de.raindancer.modules.api;

import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * The two ways to be a host.
 *
 * <p>A plugin that wants to run modules writes two lines:
 *
 * <pre>{@code
 * // onEnable
 * ModuleHost host = ModuleHosts.embedded(this, Modules.registry());
 * Modules.registry().enableAll(module -> new LiveModuleSession(host, module));
 *
 * // onDisable
 * Modules.shutdown();
 * }</pre>
 */
public final class ModuleHosts {

    private ModuleHosts() {
    }

    /**
     * For the standard wrapper: the module is the plugin.
     *
     * <p>Its files go straight into the plugin's data folder — <em>unless</em> the jar happens to carry
     * more than one module, in which case they cannot all own the same {@code config.yml} and each gets a
     * subfolder instead. Deciding it from the module count rather than from a flag means the right thing
     * happens without the wrapper being told.
     */
    public static ModuleHost standalone(Plugin plugin, ModuleRegistry registry) {
        Objects.requireNonNull(plugin, "a host needs a plugin");
        Objects.requireNonNull(registry, "a host needs a registry");
        boolean alone = registry.declared().size() == 1;
        ModuleLayout layout = alone
                ? ModuleLayout.owningFolder(plugin.getDataFolder().toPath())
                : ModuleLayout.sharedFolder(plugin.getDataFolder().toPath());
        return new Host(plugin.getName(), plugin, layout, registry);
    }

    /** For a plugin that has its own features as well as modules. */
    public static ModuleHost embedded(Plugin plugin, ModuleRegistry registry) {
        Objects.requireNonNull(plugin, "a host needs a plugin");
        Objects.requireNonNull(registry, "a host needs a registry");
        return new Host(plugin.getName(), plugin,
                ModuleLayout.sharedFolder(plugin.getDataFolder().toPath()), registry);
    }

    private record Host(String name, Plugin plugin, ModuleLayout layout, ModuleRegistry registry)
            implements ModuleHost {
    }
}
