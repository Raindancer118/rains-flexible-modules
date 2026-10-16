package de.raindancer.modules.ctf.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/** The permissions Capture the Flag knows. */
public final class PermissionNodes {

    /** Joining rounds, voting, the menus. */
    public static final String PLAY = "rainsctf.play";

    /** Maps, the lobby, starting and stopping rounds. */
    public static final String ADMIN = "rainsctf.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(PLAY, "Play Capture the Flag", PermissionDefault.TRUE),
                new Permission(ADMIN, "Set up Capture the Flag maps and the lobby, start and stop rounds",
                        PermissionDefault.OP));
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
                // Another copy of the module added it between the look and the add.
            }
        }
        return added;
    }
}
