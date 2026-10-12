package de.raindancer.modules.arena;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.arena.command.ArenaCommand;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later. Paper fires {@code COMMANDS} during bootstrap,
 * long before any module is enabled, so the handlers hold a supplier and the state to design for is <em>registered,
 * module not running</em>.
 */
public final class ArenaCommands {

    private static volatile ArenaServices services;

    private ArenaCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("arena", "The arena: back to the hub, or back to the survival server",
                                new ArenaCommand(ArenaCommands::require))
                        .taking("leave", "hub", "admin build <world> <map>", "admin sethub [world x y z yaw pitch]",
                                "admin pit <name> floor|spawn|clearspawns|remove", "admin info"));
    }

    static void ready(ArenaServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static ArenaServices require() {
        ArenaServices live = services;
        if (live == null) {
            throw new IllegalStateException("the arena module is not running");
        }
        return live;
    }
}
