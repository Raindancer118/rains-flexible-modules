package de.raindancer.modules.homes.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <h2>Every node is the one the old plugin used</h2>
 * As load-bearing as the config paths. An upgrading server has {@code homes.limit.10} granted to a
 * rank in a permissions plugin, and a renamed node would silently take those homes away — leaving a
 * player who had ten with three and nothing on screen to explain it. So: {@code homes.*}, not
 * {@code rainshomes.*}, however much the second would match the plugin's name.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Registering is idempotent, and it happens before
 * anything asks — an unregistered node resolves to "operators only", which would refuse homes to
 * every ordinary player.
 */
public final class PermissionNodes {

    /** Using homes at all. On by default: a home nobody can set is a feature nobody has. */
    public static final String USE = "homes.use";

    /**
     * No limit on how many.
     *
     * <p>Deliberately <b>not</b> op-by-default. An admin should not silently inherit an uncapped
     * limit — the number they see is then not the number anybody else sees, which makes the feature
     * impossible to test from the inside.
     */
    public static final String UNLIMITED = "homes.unlimited";

    /** Skipping the standing-still wait. */
    public static final String BYPASS_WARMUP = "homes.bypass.warmup";

    /** Skipping the wait between teleports. */
    public static final String BYPASS_COOLDOWN = "homes.bypass.cooldown";

    /**
     * The prefix of a numbered node: {@code homes.limit.10} means ten.
     *
     * <p>Read by Core's {@code NumberedLimit}, which walks what has actually been granted rather than
     * asking {@code hasPermission} per number — see that class for the operator bug that forces it.
     */
    public static final String LIMIT_PREFIX = "homes.limit.";

    private PermissionNodes() {
    }

    /**
     * The nodes worth declaring.
     *
     * <p>{@link #LIMIT_PREFIX} is deliberately absent: declaring a hundred of them to make them
     * visible in a permissions plugin's list is a hundred lines to buy very little, and they work
     * undeclared. What matters is that nothing <em>asks</em> for an undeclared one — which is exactly
     * what {@code NumberedLimit} arranges.
     */
    public static List<Permission> declared() {
        return List.of(
                new Permission(USE,
                        "Set homes, go to them, and open the list of them",
                        PermissionDefault.TRUE),
                new Permission(UNLIMITED,
                        "Have as many homes as you like",
                        PermissionDefault.FALSE),
                new Permission(BYPASS_WARMUP,
                        "Go home without standing still first",
                        PermissionDefault.OP),
                new Permission(BYPASS_COOLDOWN,
                        "Go home again without waiting",
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
