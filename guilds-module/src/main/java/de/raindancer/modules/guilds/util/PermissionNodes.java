package de.raindancer.modules.guilds.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody. Registered in code, since a hosted module has no descriptor. */
public final class PermissionNodes {

    /** Being in a guild at all: joining, chatting, the menu. */
    public static final String USE = "rainsguilds.use";

    /** Founding one. */
    public static final String FOUND = "rainsguilds.found";

    /** Going to the guild home without waiting between trips. */
    public static final String HOME_NO_WAIT = "rainsguilds.home.nowait";

    /** Staff: any guild's page, disbanding, renaming, setting experience or the leader. */
    public static final String ADMIN = "rainsguilds.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Join guilds and use /guild", PermissionDefault.TRUE),
                new Permission(FOUND, "Found a guild", PermissionDefault.TRUE),
                new Permission(HOME_NO_WAIT, "Go to the guild home without waiting between trips", PermissionDefault.FALSE),
                new Permission(ADMIN, "Look after any guild: disband, rename, set experience or the leader", PermissionDefault.OP));
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
