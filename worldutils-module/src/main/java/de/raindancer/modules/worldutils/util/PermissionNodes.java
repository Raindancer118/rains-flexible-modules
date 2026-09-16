package de.raindancer.modules.worldutils.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Every node defaults to operators: moving between
 * worlds skips every portal, gate and journey a server was built around, so it is granted, not assumed.
 */
public final class PermissionNodes {

    /** {@code /w} for yourself, and the list of worlds. */
    public static final String WORLD = "rainsworldutils.world";

    /** {@code /w <world> <players>} — somebody else, or a selector. */
    public static final String WORLD_OTHERS = "rainsworldutils.world.others";

    /** {@code /dim} for yourself. */
    public static final String DIMENSION = "rainsworldutils.dimension";

    /** {@code /dim <dimension> <players>}. */
    public static final String DIMENSION_OTHERS = "rainsworldutils.dimension.others";

    /** {@code /worlds} — creating, resetting and deleting worlds, and reading their seeds. */
    public static final String ADMIN = "rainsworldutils.admin";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(WORLD, "Switch to another world with /w", PermissionDefault.OP),
                new Permission(WORLD_OTHERS, "Send other players to a world with /w", PermissionDefault.OP),
                new Permission(DIMENSION, "Switch dimension with /dim", PermissionDefault.OP),
                new Permission(DIMENSION_OTHERS, "Send other players to a dimension with /dim",
                        PermissionDefault.OP),
                new Permission(ADMIN, "Create, reset and delete worlds, and read their seed history",
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
