package de.raindancer.modules.voicebridge;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.voicebridge.command.VoiceBridgeCommand;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later — the handler exists before
 * the module does, so it holds a supplier and the state to design for is <em>registered, module not
 * running</em>.
 */
public final class VoiceBridgeCommands {

    private static volatile VoiceBridgeServices services;

    private VoiceBridgeCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("voicebridge", "The Discord voice bridge: join its group, see who is on Discord",
                                new VoiceBridgeCommand(VoiceBridgeCommands::require))
                        .aliased("vb", "discordvoice")
                        .taking("join", "leave", "status", "link", "unlink", "groups",
                                "group join <name> [password]", "group leave", "group create <name> [password] [type]",
                                "invite <player>", "reconnect"));
    }

    static void ready(VoiceBridgeServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static VoiceBridgeServices require() {
        VoiceBridgeServices live = services;
        if (live == null) {
            throw new IllegalStateException("the voicebridge module is not running");
        }
        return live;
    }
}
