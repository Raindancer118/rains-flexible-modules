package de.raindancer.modules.veintoggle.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody. Registered in code, since a hosted module has no descriptor. */
public final class PermissionNodes {

    /** Switching Veinminer on and off for oneself. */
    public static final String USE = "rainsveintoggle.use";

    /** Switching it for somebody else. */
    public static final String OTHERS = "rainsveintoggle.others";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Switch Veinminer on or off for yourself with /vein", PermissionDefault.TRUE),
                new Permission(OTHERS, "Switch Veinminer on or off for somebody else", PermissionDefault.OP));
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
