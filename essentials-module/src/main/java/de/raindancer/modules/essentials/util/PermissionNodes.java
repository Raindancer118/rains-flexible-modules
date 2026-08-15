package de.raindancer.modules.essentials.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Registering is idempotent, and it happens before
 * anything asks — an unregistered node resolves to "operators only".
 */
public final class PermissionNodes {

    public static final String SPAWN = "essentials.spawn";
    public static final String SET_SPAWN = "essentials.setspawn";
    public static final String MSG = "essentials.msg";
    public static final String IGNORE = "essentials.ignore";
    public static final String SEEN = "essentials.seen";
    public static final String NICK = "essentials.nick";
    public static final String AFK = "essentials.afk";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(SPAWN, "Teleport to the server's spawn point", PermissionDefault.TRUE),
                new Permission(SET_SPAWN, "Move the server's spawn point to where you stand",
                        PermissionDefault.OP),
                new Permission(MSG, "Send and receive private messages", PermissionDefault.TRUE),
                new Permission(IGNORE, "Block private messages from somebody", PermissionDefault.TRUE),
                new Permission(SEEN, "Look up when somebody was last here", PermissionDefault.TRUE),
                new Permission(NICK, "Set your own nickname", PermissionDefault.TRUE),
                new Permission(AFK, "Mark yourself away from the keyboard", PermissionDefault.TRUE));
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
                // Registered by another copy between the check and the add. Nothing to do.
            }
        }
        return added;
    }
}
