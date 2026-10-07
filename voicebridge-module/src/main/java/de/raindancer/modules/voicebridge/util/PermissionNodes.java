package de.raindancer.modules.voicebridge.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody. Registered in code so a module hosted inside another plugin
 * still has its nodes; an unregistered node would quietly mean "operators only".
 */
public final class PermissionNodes {

    /** Opening the page and joining or leaving the bridge group. */
    public static final String USE = "rainsvoicebridge.use";

    /** Reconnecting the bot after changing the token or the channel. */
    public static final String ADMIN = "rainsvoicebridge.admin";

    private PermissionNodes() {
    }

    /**
     * One of Simple Voice Chat's own nodes ({@code voicechat.groups}, {@code voicechat.listen}). SVC
     * treats them as everybody's unless taken away, and on Paper does not register them — and Bukkit
     * reads an unregistered node as operators only. So: allowed unless explicitly set to false.
     */
    public static boolean svc(Permissible who, String node) {
        return !who.isPermissionSet(node) || who.hasPermission(node);
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Open the voice bridge page and join or leave the Discord group",
                        PermissionDefault.TRUE),
                new Permission(ADMIN, "Reconnect the Discord bot", PermissionDefault.OP));
    }

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
