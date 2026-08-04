package de.raindancer.modules.warp.util;

import de.raindancer.modules.warp.model.WarpAccess;
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
 * node resolves to "operators only" and would refuse the warp menu to every ordinary player.
 */
public final class PermissionNodes {

    /** Warping at all. On by default: a warp list nobody can open is a feature nobody has. */
    public static final String USE = "rainswarps.warp.use";

    /** Making, moving, retagging and deleting warps. */
    public static final String MANAGE = "rainswarps.warp.manage";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE,
                        "Open the warp menu and go to a warp",
                        PermissionDefault.TRUE),
                new Permission(MANAGE,
                        "Make, move, retag and delete warps, and reach every one of them",
                        PermissionDefault.OP),
                // Declared so it shows up in a permissions plugin's list of known nodes. Without
                // that an admin has to know the string from the documentation to grant it, and the
                // staff warps are the ones somebody most needs to grant on the first day.
                new Permission(WarpAccess.STAFF_PERMISSION,
                        "Use the warps marked staff only",
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
