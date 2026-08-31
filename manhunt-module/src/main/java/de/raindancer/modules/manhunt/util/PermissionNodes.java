package de.raindancer.modules.manhunt.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody — registered programmatically, the same reasoning
 * {@code chained-module}'s own {@code PermissionNodes} gives: idempotent, and done before anything
 * asks, since an unregistered node resolves to "operators only".
 */
public final class PermissionNodes {

    /** Seeing the lobby screen, joining a side, watching the clock. */
    public static final String USE = "rainsmanhunt.manhunt.use";

    /** Starting, stopping and resetting a hunt, and assigning who is on which side. */
    public static final String ADMIN = "rainsmanhunt.manhunt.admin";

    /**
     * {@code /whitelist open} and {@code /whitelist close} — deliberately its own node rather than
     * {@code bukkit.command.whitelist}, the vanilla one: the whole point is that a Runner may open and
     * close the server's doors without being handed full whitelist administration (adding, removing,
     * listing anybody by name) — see {@code WhitelistCommand}'s own javadoc on the passthrough.
     */
    public static final String WHITELIST = "rainsmanhunt.manhunt.whitelist";

    /** Throwing a {@code ChaosAction} at a running hunt, from the console command or the menu. */
    public static final String CHAOS = "rainsmanhunt.manhunt.chaos";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "See the Manhunt lobby and join a side", PermissionDefault.TRUE),
                new Permission(ADMIN, "Start, stop and reset a hunt, and assign sides", PermissionDefault.OP),
                new Permission(WHITELIST, "Open and close the server whitelist for a hunt",
                        PermissionDefault.OP),
                new Permission(CHAOS, "Throw a chaos action at a running hunt", PermissionDefault.OP));
    }

    /**
     * Registers whatever is not registered already.
     *
     * @return how many were added, for the line in the log
     */
    public static int register(Server server) {
        if (server == null) {
            return 0;
        }
        int added = 0;
        for (Permission permission : declared()) {
            if (server.getPluginManager().getPermission(permission.getName()) != null) {
                continue;
            }
            try {
                server.getPluginManager().addPermission(permission);
                added++;
            } catch (IllegalArgumentException alreadyThere) {
                // Registered by another copy between the check and the add. Nothing to do.
            }
        }
        return added;
    }
}
