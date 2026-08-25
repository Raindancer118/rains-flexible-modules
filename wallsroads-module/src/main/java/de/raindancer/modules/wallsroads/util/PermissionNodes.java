package de.raindancer.modules.wallsroads.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody. Registered programmatically rather than in a descriptor —
 * see {@code mannequin-module}'s own copy of this class for why.
 */
public final class PermissionNodes {

    /** Using {@code /wallsroads} at all. */
    public static final String USE = "rainswallsandroads.use";

    /** Marking out a new wall or road, when {@code openCreation} is off. */
    public static final String CREATE = "rainswallsandroads.create";

    /** Editing or removing any wall/road, not only your own. */
    public static final String MANAGE_ANY = "rainswallsandroads.manage.any";

    /** Building without the blocks being taken out of your inventory. */
    public static final String BUILD_FREE = "rainswallsandroads.build.free";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Use /wallsroads", PermissionDefault.TRUE),
                new Permission(CREATE, "Mark out a new wall or road", PermissionDefault.OP),
                new Permission(MANAGE_ANY, "Edit or remove any wall or road", PermissionDefault.OP),
                new Permission(BUILD_FREE, "Build without paying for the blocks", PermissionDefault.OP));
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
                // Registered by another copy between the check and the add. Nothing to do.
            }
        }
        return added;
    }
}
