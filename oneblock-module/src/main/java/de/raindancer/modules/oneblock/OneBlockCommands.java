package de.raindancer.modules.oneblock;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.oneblock.command.IslandAdminCommand;
import de.raindancer.modules.oneblock.command.IslandCommand;
import de.raindancer.modules.oneblock.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap. Paper fires {@code COMMANDS} before any module is enabled, so the handler
 * holds a supplier of the services; the state to design for is <em>registered, module not running</em>.
 */
public final class OneBlockCommands {

    private static volatile OneBlockServices services;

    private OneBlockCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(ModuleCommand.of("island", "Your OneBlock island, and invites from anywhere on the network",
                                new IslandCommand(OneBlockCommands::require))
                        .aliased("is")
                        .taking("menu", "nether", "end", "unlock <nether|end>", "upgrades [dimension]", "team",
                                "invite <player>", "guest <player>", "accept [player]", "deny [player]",
                                "kick <player>", "leave", "setspawn", "info [player]", "sidebar"),
                ModuleCommand.of("islandadmin", "Keep islands up or down, their auto-unload, shards, migration",
                                new IslandAdminCommand(OneBlockCommands::require))
                        .aliased("isa", "obadmin")
                        .needing(PermissionNodes.ADMIN)
                        .taking("list", "info <island>", "up <island> [time|forever]", "down <island> [time|forever]",
                                "release <island>", "autounload <island> on|off", "tp <island> [dimension]",
                                "shards <island> <amount>", "reload", "migrate [confirm]"));
    }

    static void ready(OneBlockServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static OneBlockServices require() {
        OneBlockServices live = services;
        if (live == null) {
            throw new IllegalStateException("the oneblock module is not running");
        }
        return live;
    }
}
