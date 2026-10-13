package de.raindancer.modules.twitch.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** What this module asks about somebody; registered in code so a hosted module has its nodes too. */
public final class PermissionNodes {

    /** {@code /twitch}: status, test announcements, reading the login again. */
    public static final String ADMIN = "rainstwitch.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(new Permission(ADMIN, "See the Twitch watch, test the announcements, read the login again",
                PermissionDefault.OP));
    }

    public static int register(Server server) {
        int added = 0;
        for (Permission node : declared()) {
            if (server.getPluginManager().getPermission(node.getName()) == null) {
                server.getPluginManager().addPermission(node);
                added++;
            }
        }
        return added;
    }
}
