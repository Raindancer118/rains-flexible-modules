package de.raindancer.modules.performance;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.performance.command.FarmsCommand;
import de.raindancer.modules.performance.command.PerfCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any
 * plugin is enabled, so the commands point at {@link #require} until then.
 */
public final class PerformanceCommands {

    private static volatile PerformanceServices services;

    private PerformanceCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("perf", "How the server is doing, and what slows it down",
                                new PerfCommand(PerformanceCommands::require))
                        .aliased("lag", "performance")
                        .taking("(nothing) — how it is doing", "report — look now", "reports — the last ones",
                                "show <n>", "fix <n> <finding>|distance", "undo — the simulation distance back",
                                "tp <n> <finding>", "alerts — reports in chat on or off for you")
                        .needing("rainsperformance.inspect"),
                ModuleCommand.of("farms", "Lists the most crowded chunks", new FarmsCommand(PerformanceCommands::require))
                        .taking("(nothing) — chunks with 40 or more entities", "<least> — with at least that many")
                        .needing("rainsperformance.inspect"));
    }

    static void ready(PerformanceServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static PerformanceServices require() {
        PerformanceServices live = services;
        if (live == null) {
            throw new IllegalStateException("the performance module is not running");
        }
        return live;
    }
}
