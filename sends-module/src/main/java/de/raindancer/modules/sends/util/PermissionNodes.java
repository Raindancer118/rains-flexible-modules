package de.raindancer.modules.sends.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody. Registered in code rather than in a descriptor so a module hosted inside
 * another plugin still has its nodes, and before anything asks: an unregistered node resolves to "operators
 * only", which would look exactly like the feature being broken for everybody else.
 *
 * <p>Sending defaults to everybody — it is two players agreeing to exchange items, with nothing created out of
 * nowhere.
 */
public final class PermissionNodes {

    /** Making a send, and everything that follows from one: accepting, declining, cancelling, listing. */
    public static final String USE = "rainssends.use";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(new Permission(USE, "Send items to other players and receive them",
                PermissionDefault.TRUE));
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
