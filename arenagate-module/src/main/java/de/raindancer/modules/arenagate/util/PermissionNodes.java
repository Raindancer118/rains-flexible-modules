package de.raindancer.modules.arenagate.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody, registered in code so a hosted module still has its nodes. */
public final class PermissionNodes {

    /** Going to the arena. */
    public static final String USE = "rainsarenagate.use";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(new Permission(USE, "Go to the arena server", PermissionDefault.TRUE));
    }

    /** @return how many were added, for the line in the log */
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
                // Registered by another copy between the check and the add.
            }
        }
        return added;
    }
}
