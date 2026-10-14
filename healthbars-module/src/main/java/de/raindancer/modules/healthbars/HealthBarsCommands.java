package de.raindancer.modules.healthbars;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.healthbars.command.HealthBarsCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any plugin
 * is enabled, so the command points at {@link #require} until then.
 */
public final class HealthBarsCommands {

    private static volatile HealthBarsServices services;

    private HealthBarsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(ModuleCommand.of("healthbars", "Shows or hides the health bars over players and mobs, for you",
                        new HealthBarsCommand(HealthBarsCommands::require))
                .aliased("hp")
                .taking("(nothing) — switches them", "on | off | status", "style [hearts | bar] — how they look"));
    }

    static void ready(HealthBarsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static HealthBarsServices require() {
        HealthBarsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the health bars module is not running");
        }
        return live;
    }
}
