package de.raindancer.modules.arena.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody. Registered in code rather than in a descriptor so a module hosted inside
 * another plugin still has its nodes, and before anything asks: an unregistered node resolves to "operators only".
 */
public final class PermissionNodes {

    /** Going into the arena and everything done there. */
    public static final String USE = "rainsarena.use";

    /** Building arena worlds, setting the hub, marking pits. */
    public static final String ADMIN = "rainsarena.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Go into the arena and fight there", PermissionDefault.TRUE),
                new Permission(ADMIN, "Build arena worlds, set the hub and mark pits", PermissionDefault.OP));
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
