package de.raindancer.modules.invsnap.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, so a module hosted inside another
 * plugin still has its nodes. Idempotent, and done before anything asks — an unregistered node
 * resolves to "operators only", which would refuse the command to every ordinary player.
 *
 * <h2>Admin-only, by design</h2>
 * Restoring a snapshot is a support action taken on somebody else's inventory, not something a
 * player does for themselves — see {@code Project.md}'s note on this module. Both nodes default to
 * operator rather than everyone.
 */
public final class PermissionNodes {

    /** Browsing any player's snapshot history. */
    public static final String BROWSE = "rainsinvsnap.invsnap.browse";

    /** Restoring a snapshot into a live inventory. */
    public static final String RESTORE = "rainsinvsnap.invsnap.restore";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(BROWSE, "Browse a player's inventory snapshot history",
                        PermissionDefault.OP),
                new Permission(RESTORE, "Restore a player's inventory from a snapshot",
                        PermissionDefault.OP));
    }

    /**
     * Registers whatever is not registered already.
     *
     * @return how many were added, for the line in the log
     */
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
