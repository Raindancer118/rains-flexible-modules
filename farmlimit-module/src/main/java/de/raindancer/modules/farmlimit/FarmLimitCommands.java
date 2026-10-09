package de.raindancer.modules.farmlimit;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.farmlimit.command.FarmsCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any
 * plugin is enabled, so the command points at {@link #require} until then.
 */
public final class FarmLimitCommands {

    private static volatile FarmLimitServices services;

    private FarmLimitCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("farms", "Lists the most crowded chunks", new FarmsCommand(FarmLimitCommands::require))
                        .aliased("farmlimit")
                        .taking("(nothing) — chunks with 40 or more entities", "<least> — with at least that many")
                        .needing("rainsfarmlimit.inspect"));
    }

    static void ready(FarmLimitServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static FarmLimitServices require() {
        FarmLimitServices live = services;
        if (live == null) {
            throw new IllegalStateException("the farmlimit module is not running");
        }
        return live;
    }
}
