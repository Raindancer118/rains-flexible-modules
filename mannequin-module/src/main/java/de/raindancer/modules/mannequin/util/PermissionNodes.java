package de.raindancer.modules.mannequin.util;

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
 */
public final class PermissionNodes {

    /** Using {@code /mannequin} at all. */
    public static final String USE = "rainsmannequins.mannequin.use";

    /** Creating a mannequin, when {@code MannequinSettings#openCreation} is off. Operators by default. */
    public static final String CREATE = "rainsmannequins.mannequin.create";

    /** Removing any mannequin, not only your own. */
    public static final String REMOVE_ANY = "rainsmannequins.mannequin.remove.any";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Use /mannequin", PermissionDefault.TRUE),
                new Permission(CREATE, "Create a training dummy", PermissionDefault.OP),
                new Permission(REMOVE_ANY, "Remove any player's training dummy",
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
