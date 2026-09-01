package de.raindancer.modules.xpbottle;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.xpbottle.command.XpBottleCommand;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <p>Paper fires its {@code COMMANDS} event during the bootstrap phase, long before any module is
 * enabled, so the handler exists before the module does. It therefore holds a supplier of this
 * class rather than the services themselves, and the state to design for is <em>registered, module
 * not running</em> — which a player reaches three ways: before it starts, after it failed, and after
 * it stopped.
 */
public final class XpBottleCommands {

    private static volatile XpBottleServices services;

    private XpBottleCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("xpbottle", "Bottle experience, and hand out siphon bottles",
                                new XpBottleCommand(XpBottleCommands::require))
                        .aliased("xpb")
                        .taking("give <player> [tier]"));
    }

    static void ready(XpBottleServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static XpBottleServices require() {
        XpBottleServices live = services;
        if (live == null) {
            throw new IllegalStateException("the xpbottle module is not running");
        }
        return live;
    }
}
