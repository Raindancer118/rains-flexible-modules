package de.raindancer.modules.performance.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody. Registered in code, since a hosted module has no descriptor. */
public final class PermissionNodes {

    /** Seeing how the server is doing, reports, and where entities pile up. */
    public static final String INSPECT = "rainsperformance.inspect";

    /** Applying a report's fixes — removing entities, changing the simulation distance. */
    public static final String FIX = "rainsperformance.fix";

    /** Being told about lag in chat as it happens. */
    public static final String NOTIFY = "rainsperformance.notify";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(INSPECT, "See /perf, reports, and the most crowded chunks with /farms", PermissionDefault.OP),
                new Permission(FIX, "Apply a report's fixes", PermissionDefault.OP),
                new Permission(NOTIFY, "Be told about lag in chat", PermissionDefault.OP));
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
