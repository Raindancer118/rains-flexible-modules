package de.raindancer.modules.xaeromap.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, so a module hosted inside another plugin
 * still has its nodes. Idempotent, and done before anything asks — an unregistered node resolves to
 * "operators only", which on Bukkit is also what makes {@code hasPermission} answer true for every
 * operator whether or not anybody granted it.
 *
 * <h2>Two nodes, and only one of them is for players</h2>
 * {@link #REFRESH} is somebody fixing their own map when their client has got itself into a state, and
 * defaults to everyone: it costs one resync of that one player, and a player who cannot do it will ask
 * an admin to do it for them, which costs more. {@link #ADMIN} covers the diagnostic and resyncing
 * <em>other</em> people, and defaults to operator.
 */
public final class PermissionNodes {

    /** Asking for your own map to be sent again. */
    public static final String REFRESH = "rainsxaeromap.xaeromap.refresh";

    /** The status page, and resyncing everybody. */
    public static final String ADMIN = "rainsxaeromap.xaeromap.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(REFRESH, "Ask for your own map data to be sent again",
                        PermissionDefault.TRUE),
                new Permission(ADMIN, "Inspect map support and resync every player",
                        PermissionDefault.OP));
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
