package de.raindancer.modules.rtp.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Registering is idempotent, and it happens before
 * anything asks: an unregistered node resolves to "operators only", which would refuse {@code /rtp} to
 * every ordinary player.
 */
public final class PermissionNodes {

    /** Using the command at all. On by default: a random teleport nobody can reach is not a feature. */
    public static final String USE = "rainsrtp.rtp.use";

    /** Skipping the wait between goes. */
    public static final String BYPASS_COOLDOWN = "rainsrtp.rtp.bypass.cooldown";

    /** Skipping the stand-still-first warm-up. */
    public static final String BYPASS_WARMUP = "rainsrtp.rtp.bypass.warmup";

    /** Preparing locations ahead of time by hand, with {@code /rtp prepare}. */
    public static final String PREPARE = "rainsrtp.rtp.prepare";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE, "Use /rtp to go somewhere random", PermissionDefault.TRUE),
                new Permission(BYPASS_COOLDOWN, "Skip the wait between random teleports",
                        PermissionDefault.OP),
                new Permission(BYPASS_WARMUP, "Skip the stand-still warm-up before a random teleport",
                        PermissionDefault.OP),
                new Permission(PREPARE, "Prepare random-teleport locations ahead of time by hand",
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
