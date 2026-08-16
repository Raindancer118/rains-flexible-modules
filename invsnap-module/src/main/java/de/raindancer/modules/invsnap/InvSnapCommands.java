package de.raindancer.modules.invsnap;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.invsnap.command.InvSnapCommand;
import de.raindancer.modules.invsnap.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later. See {@code
 * MannequinCommands} for the full reasoning on why the command cannot simply be handed the
 * services up front.
 */
public final class InvSnapCommands {

    private static volatile InvSnapServices services;

    private InvSnapCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("invsnap", "Browse and restore a player's inventory snapshots",
                                new InvSnapCommand(InvSnapCommands::require))
                        .needing(PermissionNodes.BROWSE)
                        .taking("[player]")
                        .auditUsage());
    }

    static void ready(InvSnapServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static InvSnapServices require() {
        InvSnapServices live = services;
        if (live == null) {
            throw new IllegalStateException("the invsnap module is not running");
        }
        return live;
    }
}
