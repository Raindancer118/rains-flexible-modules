package de.raindancer.modules.mannequin;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.mannequin.command.MannequinCommand;
import de.raindancer.modules.mannequin.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later. See {@code RtpCommands}
 * for the full reasoning on why the command cannot simply be handed the services up front.
 */
public final class MannequinCommands {

    private static volatile MannequinServices services;

    private MannequinCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("mannequin", "Create, dress and inspect training dummies",
                                new MannequinCommand(MannequinCommands::require))
                        .needing(PermissionNodes.USE)
                        .taking("create [kind]", "remove <id>", "loadout <id>", "skin <id>", "stats <id>", "list"));
    }

    static void ready(MannequinServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static MannequinServices require() {
        MannequinServices live = services;
        if (live == null) {
            throw new IllegalStateException("the mannequin module is not running");
        }
        return live;
    }
}
