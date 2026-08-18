package de.raindancer.modules.speedrun.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module's commands ask about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, same reasoning as {@code rtp-module}'s
 * own {@code PermissionNodes}: a module may be hosted inside another plugin with no descriptor of its
 * own, and an unregistered node resolves to "operators only" — which would refuse {@code /lemmemove}
 * and {@code /speedrunspectate} to every ordinary player, both meant for anybody racing.
 */
public final class PermissionNodes {

    /** Releasing yourself from the movement freeze. On by default: a racer stuck by a bug should not
     *  need an operator standing by to type a command for them. */
    public static final String LEMMEMOVE_SELF = "rainsspeedrun.lemmemove.self";

    /** Releasing somebody else. Op-only: this is the one that can actually hand out a head start. */
    public static final String LEMMEMOVE_OTHERS = "rainsspeedrun.lemmemove.others";

    /** {@code /starthere} and {@code /speedrunreset} — changing the map for everybody, or throwing it
     *  away. Op-only. */
    public static final String ADMIN = "rainsspeedrun.admin";

    /** Registering as not racing. On by default, the same reasoning as {@link #LEMMEMOVE_SELF}. */
    public static final String SPECTATE = "rainsspeedrun.spectate";

    /** Teleporting yourself to the lobby world. On by default: this is the only way there at all —
     *  nothing else gets a player into the lobby world in the first place. */
    public static final String JOIN = "rainsspeedrun.join";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(LEMMEMOVE_SELF, "Escape your own speedrun movement freeze",
                        PermissionDefault.TRUE),
                new Permission(LEMMEMOVE_OTHERS, "Release somebody else from the speedrun movement freeze",
                        PermissionDefault.OP),
                new Permission(ADMIN, "Set the speedrun start point, and force-reset a run",
                        PermissionDefault.OP),
                new Permission(SPECTATE, "Register as not racing the next speedrun",
                        PermissionDefault.TRUE),
                new Permission(JOIN, "Teleport to the speedrun lobby world",
                        PermissionDefault.TRUE));
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
