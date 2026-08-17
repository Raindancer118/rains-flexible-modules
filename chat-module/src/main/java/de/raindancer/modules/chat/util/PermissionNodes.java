package de.raindancer.modules.chat.util;

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

    /** Reaches past a caps or repeat refusal, a cooldown, and a server-wide slowmode. */
    public static final String BYPASS_FILTERS = "chat.bypass.filters";

    /** Still gets through while chat is frozen. */
    public static final String BYPASS_FREEZE = "chat.bypass.freeze";

    /** {@code /chat clear}, {@code /chat freeze} and {@code /chat slowmode}. */
    public static final String ADMIN = "chat.admin";

    /** {@code /announce}. */
    public static final String ANNOUNCE = "chat.announce";

    /** {@code /chathistory}. */
    public static final String HISTORY = "chat.history";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(BYPASS_FILTERS,
                        "Never refused for shouting, repeating a message, a cooldown or slowmode",
                        PermissionDefault.OP),
                new Permission(BYPASS_FREEZE, "Still able to talk while chat is frozen",
                        PermissionDefault.OP),
                new Permission(ADMIN, "Clear, freeze and slow down chat", PermissionDefault.OP),
                new Permission(ANNOUNCE, "Broadcast a banner every online player sees and hears",
                        PermissionDefault.OP),
                new Permission(HISTORY, "See chat that happened while you were away",
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
                // Registered by another copy between the check and the add. Nothing to do.
            }
        }
        return added;
    }
}
