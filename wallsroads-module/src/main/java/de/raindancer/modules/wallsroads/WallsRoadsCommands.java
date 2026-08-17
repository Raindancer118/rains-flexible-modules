package de.raindancer.modules.wallsroads;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.wallsroads.command.WallsRoadsCommand;
import de.raindancer.modules.wallsroads.util.PermissionNodes;

import java.util.List;

/** What this module declares at bootstrap, and how it is filled in later. See {@code MannequinCommands}. */
public final class WallsRoadsCommands {

    private static volatile WallsRoadsServices services;

    private WallsRoadsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("wallsroads", "Mark out, build and edit town walls and roads",
                                new WallsRoadsCommand(WallsRoadsCommands::require))
                        .aliased("wr")
                        .needing(PermissionNodes.USE)
                        .taking("wall new", "wall <name>", "road new", "road <name>", "cancel", "list"));
    }

    static void ready(WallsRoadsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static WallsRoadsServices require() {
        WallsRoadsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the walls-and-roads module is not running");
        }
        return live;
    }
}
