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
     * One of Simple Voice Chat's own nodes ({@code voicechat.groups}, {@code voicechat.listen}), decided
     * exactly as SVC decides it on Bukkit: a node an owner set wins, otherwise SVC's default of
     * everybody. SVC does not register these on Paper, so asking by name would read "operators only".
     */
    public static boolean svc(Permissible who, String node) {
        return who.hasPermission(new Permission(node, PermissionDefault.TRUE));
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
