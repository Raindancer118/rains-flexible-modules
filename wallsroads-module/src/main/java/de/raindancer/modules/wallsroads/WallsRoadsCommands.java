package de.raindancer.modules.wallsroads;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.wallsroads.command.ShortcutCommand;
import de.raindancer.modules.wallsroads.command.WallsRoadsCommand;
import de.raindancer.modules.wallsroads.screen.WallsRoadsListMenu;
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
                        .taking("wall new", "wall <name>", "road new", "road <name>", "map", "manual",
                                "config", "cancel", "list"),

                // Two more commands rather than two more aliases: Paper hands a command its arguments
                // and not the word that was typed, so one handler cannot tell /walls from /roads —
                // and an alias that opens the same page as /wallsroads is a second name for the front
                // page rather than a way to the walls.
                ModuleCommand.of("walls", "Your walls, or one by name",
                                new ShortcutCommand(WallsRoadsCommands::require,
                                        WallsRoadsListMenu.Filter.WALLS))
                        .aliased("wall")
                        .needing(PermissionNodes.USE)
                        .taking("<name>"),

                ModuleCommand.of("roads", "Your roads, or one by name",
                                new ShortcutCommand(WallsRoadsCommands::require,
                                        WallsRoadsListMenu.Filter.ROADS))
                        .aliased("road")
                        .needing(PermissionNodes.USE)
                        .taking("<name>"));
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
