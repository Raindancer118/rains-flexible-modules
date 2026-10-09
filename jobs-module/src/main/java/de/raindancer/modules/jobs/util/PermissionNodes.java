package de.raindancer.modules.jobs.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody. Registered in code, since a hosted module has no descriptor. */
public final class PermissionNodes {

    /** Using the board: handing in, fishing for goals, being paid. */
    public static final String USE = "rainsjobs.use";

    /** Ending or starting goals, reading jobs.yml again. */
    public static final String ADMIN = "rainsjobs.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Work on the server's goals with /jobs", PermissionDefault.TRUE),
                new Permission(ADMIN, "End and start goals, read jobs.yml again", PermissionDefault.OP));
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
