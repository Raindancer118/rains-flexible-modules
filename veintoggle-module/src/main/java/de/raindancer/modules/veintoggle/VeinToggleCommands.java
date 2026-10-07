package de.raindancer.modules.veintoggle;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.veintoggle.command.VeinCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any
 * plugin is enabled, so the command points at {@link #require} until then.
 */
public final class VeinToggleCommands {

    private static volatile VeinToggleServices services;

    private VeinToggleCommands() {
    }

    /** {@code /vein}, not {@code /veinminer}: that one is Veinminer's own. */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("vein", "Switches Veinminer on or off for you",
                                new VeinCommand(VeinToggleCommands::require))
                        .aliased("veintoggle")
                        .taking("(nothing) — switch it", "on | off | status", "<player> [on|off] — for staff")
                        .needing("rainsveintoggle.use"));
    }

    static void ready(VeinToggleServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static VeinToggleServices require() {
        VeinToggleServices live = services;
        if (live == null) {
            throw new IllegalStateException("the veintoggle module is not running");
        }
        return live;
    }
}
