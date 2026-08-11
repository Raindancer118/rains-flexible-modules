package de.raindancer.modules.chained.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Registering is idempotent — two copies of the
 * module on one server is a real state — and it happens before anything asks, since an unregistered
 * node resolves to "operators only".
 */
public final class PermissionNodes {

    /** Seeing your own chain's status. On by default: a status page nobody can open is no feature. */
    public static final String USE = "rainschained.chain.use";

    /** Pairing players, starting, stopping and resetting a run. */
    public static final String ADMIN = "rainschained.chain.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "See your own chain's status", PermissionDefault.TRUE),
                new Permission(ADMIN, "Pair players, start, stop and reset a run", PermissionDefault.OP));
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
