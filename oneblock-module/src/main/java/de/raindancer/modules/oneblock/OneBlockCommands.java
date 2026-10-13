package de.raindancer.modules.oneblock;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.oneblock.command.IslandCommand;

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
                .taking("invite <player>", "guest <player>", "accept [player]", "deny [player]", "sidebar"));
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
