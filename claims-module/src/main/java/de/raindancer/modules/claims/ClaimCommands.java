package de.raindancer.modules.claims;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.claims.command.ClaimAdminCommand;
import de.raindancer.modules.claims.command.ClaimCommand;

import java.util.List;

/**
 * The commands, built at bootstrap and pointed at services that do not exist yet.
 *
 * <h2>Why this class exists at all</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase — before the plugin object exists,
 * let alone this module's services. A command handler registered in {@code onEnable} never runs at all: no
 * warning, no exception, the command simply does not exist. So the handlers must be built early and must not
 * capture anything.
 *
 * <p>Hence the supplier. The commands hold a way to <em>ask</em> for the services, and {@link #ready} fills it in
 * when the module enables. Between the two, {@code ModuleCommands.guarded} answers a player with one red line
 * saying the module has not started rather than a {@link NullPointerException} in the console.
 */
public final class ClaimCommands {

    private static volatile ClaimServices services;

    private ClaimCommands() {
    }

    /** What the module declares at bootstrap. */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("claim", "Your land: mark it out, trust people, keep others out",
                        new ClaimCommand(ClaimCommands::require)).aliased("claims"),
                ModuleCommand.of("claimadmin", "Land administration for the server owner",
                        new ClaimAdminCommand(ClaimCommands::require)).aliased("cadmin"));
    }

    /** Called when the module enables, after which the commands work. */
    static void ready(ClaimServices live) {
        services = live;
    }

    /** Called when it stops, so a command run afterwards refuses rather than using half-shut services. */
    static void stopped() {
        services = null;
    }

    /**
     * The services, or an exception naming the real problem.
     *
     * <p>Should be unreachable: the commands are guarded, so a player cannot get this far while the module is
     * not running. If it ever does throw, the message is the useful half — "not started" rather than a null
     * dereference forty frames deep in a menu.
     */
    private static ClaimServices require() {
        ClaimServices live = services;
        if (live == null) {
            throw new IllegalStateException("the claims module is not running");
        }
        return live;
    }
}
