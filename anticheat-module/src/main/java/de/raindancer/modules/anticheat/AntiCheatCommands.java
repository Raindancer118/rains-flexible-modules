package de.raindancer.modules.anticheat;

import de.raindancer.modules.anticheat.command.AntiCheatCommand;
import de.raindancer.modules.anticheat.util.PermissionNodes;
import de.raindancer.modules.api.ModuleCommand;

import java.util.List;

/** Declared at bootstrap, filled in when the module starts. */
public final class AntiCheatCommands {

    private static volatile AntiCheatServices services;

    private AntiCheatCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(ModuleCommand.of("anticheat", "Looks players up and runs the anti-cheat",
                        new AntiCheatCommand(AntiCheatCommands::require))
                .aliased("ac", "rac")
                .taking("(nothing) — the suspects screen", "alerts | verbose", "info <player>",
                        "log <player> [page]", "replay <player> [n]", "exempt <player> <seconds> | unexempt <player>",
                        "reset <player>", "checks", "status")
                .needing(PermissionNodes.COMMAND));
    }

    static void ready(AntiCheatServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static AntiCheatServices require() {
        AntiCheatServices live = services;
        if (live == null) {
            throw new IllegalStateException("the anticheat module is not running");
        }
        return live;
    }
}
