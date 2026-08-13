package de.raindancer.modules.worldgate.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Registering is idempotent, and it happens before
 * anything asks: an unregistered node resolves to "operators only", which would refuse {@code /worldgate
 * status} to every ordinary player.
 */
public final class PermissionNodes {

    /**
     * Reading {@code /worldgate status}. On by default, deliberately lower than {@link #ADMIN} — the
     * command itself is gated on this rather than left open, so it still resolves for an ordinary
     * player and {@code canUse} can tell them apart from "the module is not running" (see
     * {@code ModuleCommands.guarded}). Every subcommand that actually changes anything checks
     * {@link #ADMIN} for itself, the same way {@code RtpCommand} checks its own {@code prepare}
     * permission beneath the command-level {@code USE}.
     */
    public static final String STATUS = "rainsworldgate.status";

    /** Locking, opening or evacuating a dimension. */
    public static final String ADMIN = "rainsworldgate.admin";

    /** Never blocked entering or leaving a locked dimension, in either direction. */
    public static final String BYPASS = "rainsworldgate.bypass";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(STATUS, "See whether the Nether and the End are open",
                        PermissionDefault.TRUE),
                new Permission(ADMIN, "Lock, open or evacuate the Nether or the End",
                        PermissionDefault.OP),
                new Permission(BYPASS, "Never be blocked by a locked Nether or End",
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
