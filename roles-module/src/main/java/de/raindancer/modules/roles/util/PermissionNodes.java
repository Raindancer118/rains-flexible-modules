package de.raindancer.modules.roles.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody. Registered in code, since a hosted module has no descriptor. */
public final class PermissionNodes {

    /** Picking a role for oneself. */
    public static final String USE = "rainsroles.use";

    /** Skipping the wait between changes, for testing a role. */
    public static final String BYPASS = "rainsroles.bypass";

    /** Setting or clearing somebody else's role, and reading roles.yml again. */
    public static final String ADMIN = "rainsroles.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Pick your own role with /role", PermissionDefault.TRUE),
                new Permission(BYPASS, "Change role without waiting, with /role bypass", PermissionDefault.OP),
                new Permission(ADMIN, "Set anybody's role and read roles.yml again", PermissionDefault.OP));
    }

    /** Registers whatever is not registered yet. @return how many were added */
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
