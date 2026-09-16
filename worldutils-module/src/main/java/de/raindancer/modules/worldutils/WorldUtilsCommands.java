package de.raindancer.modules.worldutils;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.worldutils.command.DimensionCommand;
import de.raindancer.modules.worldutils.command.WorldCommand;
import de.raindancer.modules.worldutils.command.WorldsCommand;
import de.raindancer.modules.worldutils.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later — the same arrangement as
 * {@code WorldGateCommands}, for the same reason: Paper registers commands before any module has built
 * anything, so a command holds a supplier and asks when it is run.
 */
public final class WorldUtilsCommands {

    private static volatile WorldUtilsServices services;

    private WorldUtilsCommands() {
    }

    /**
     * The three commands.
     *
     * <p>{@code /w} is the name that was asked for. Vanilla — and a chat plugin — may also use it for
     * whispering; a server that wants both keeps {@code /msg} for that, and this still answers to
     * {@code /wtp}.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("w", "Go to another world, or send somebody there",
                                new WorldCommand(WorldUtilsCommands::require))
                        .aliased("wtp")
                        .taking("w — every loaded world, click one to go",
                                "w <world> — go there, back where you last stood in it",
                                "w <world> <player or selector> — send them")
                        .needing(PermissionNodes.WORLD),
                ModuleCommand.of("dim", "Go to the same place in another dimension",
                                new DimensionCommand(WorldUtilsCommands::require))
                        .aliased("dimension")
                        .taking("dim <overworld|nether|end> — the matching place, in this world's dimension",
                                "dim <overworld|nether|end> <player or selector> — send them")
                        .needing(PermissionNodes.DIMENSION),
                ModuleCommand.of("worlds", "Create, reset or delete worlds, and look up their seeds",
                                new WorldsCommand(WorldUtilsCommands::require))
                        .aliased("worldutils", "wu")
                        .taking("worlds list — every loaded world",
                                "worlds create <name> [overworld|nether|end|family] [seed]",
                                "worlds regen <world> [same|random|seed] [family]",
                                "worlds delete <world> [family]",
                                "worlds seeds <world> — every seed it has had",
                                "worlds info <world>")
                        .needing(PermissionNodes.ADMIN)
                        .auditUsage());
    }

    static void ready(WorldUtilsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    /** The services, or an exception the host's guard turns into one red line. */
    private static WorldUtilsServices require() {
        WorldUtilsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the worldutils module is not running");
        }
        return live;
    }
}
