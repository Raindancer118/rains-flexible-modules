package de.raindancer.modules.arenagate;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.arenagate.command.ArenaGateCommand;

import java.util.List;

/** What this module declares at bootstrap; the handlers hold a supplier, filled in once the module runs. */
public final class GateCommands {

    private static volatile GateServices services;

    private GateCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("arena", "Go to the arena server — PvP, kits, mob waves",
                                new ArenaGateCommand(GateCommands::require))
                        .taking("wave [preset] [kit]", "queue [mode] [kit] [stake]"),
                ModuleCommand.of("pvp", "Ask somebody to an arena fight, answer, or queue for one",
                                new de.raindancer.modules.arenagate.command.PvpGateCommand(GateCommands::require))
                        .taking("invite <player> [duel|sumo|waves] [kit|own] [stake] [bo3]", "accept [id]", "deny [id]",
                                "queue [mode] [kit] [stake]"));
    }

    static void ready(GateServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static GateServices require() {
        GateServices live = services;
        if (live == null) {
            throw new IllegalStateException("the arena gate is not running");
        }
        return live;
    }
}
