package de.raindancer.modules.sends;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.sends.command.SendCommand;
import de.raindancer.modules.sends.command.SendsCommand;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <p>Paper fires its {@code COMMANDS} event during the bootstrap phase, long before any module is enabled, so
 * the handlers exist before the module does and hold a supplier of this class rather than the services. The
 * state to design for is <em>registered, module not running</em>.
 */
public final class SendsCommands {

    private static volatile SendsServices services;

    private SendsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("send", "Send items to another player",
                                new SendCommand(SendsCommands::require))
                        .taking("create [player]", "accept [id]", "decline [id]", "cancel [id]"),
                ModuleCommand.of("sends", "Everything waiting to be accepted, by you or by somebody else",
                        new SendsCommand(SendsCommands::require)));
    }

    static void ready(SendsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static SendsServices require() {
        SendsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the sends module is not running");
        }
        return live;
    }
}
