package de.raindancer.modules.xaeromap;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.xaeromap.command.XaeroMapCommand;
import de.raindancer.modules.xaeromap.util.PermissionNodes;

import java.util.List;

/**
 * What this module declares at bootstrap, and how it is filled in later.
 *
 * <p>Paper asks for commands during the bootstrap phase, before anything is enabled, so the handler
 * cannot be handed the services — they do not exist yet. It holds a supplier onto this class instead,
 * and the state to design for is <em>registered, module not running</em>, which a player reaches three
 * ways: before it starts, after it failed, and after it stopped. The host wraps every command in
 * {@code ModuleCommands.guarded}, which turns the exception below into a refusal rather than a stack
 * trace in somebody's chat.
 */
public final class XaeroMapCommands {

    private static volatile XaeroMapServices services;

    private XaeroMapCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("xaeromap",
                                "What this server tells Xaero's map mods, and sending it again",
                                new XaeroMapCommand(XaeroMapCommands::require))
                        // The command itself is open, because its bare form is a player refreshing
                        // their own map; status and resync check the admin node themselves.
                        .needing(PermissionNodes.REFRESH)
                        .taking("[refresh|status|resync]")
                        .auditUsage());
    }

    static void ready(XaeroMapServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static XaeroMapServices require() {
        XaeroMapServices live = services;
        if (live == null) {
            throw new IllegalStateException("the xaeromap module is not running");
        }
        return live;
    }
}
