package de.raindancer.modules.xpbottle.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, so a module hosted inside another
 * plugin still has its nodes. Idempotent, and done before anything asks — an unregistered node
 * resolves to "operators only", which would refuse an everyday player action to every ordinary
 * player and look exactly like the feature being broken.
 *
 * <h2>Why bottling defaults to everybody</h2>
 * It is a thing a player does to their own experience, with an item they already have. A default of
 * operator would mean the module does nothing at all on a server whose owner installed it and read
 * no further, which is the failure mode a permission default exists to avoid. Being <em>given</em> a
 * siphon bottle out of nowhere is the staff action, and that one is operator.
 */
public final class PermissionNodes {

    /** Drawing your own experience into a bottle. */
    public static final String FILL = "rainsxpbottles.bottle.fill";

    /** Pouring a bottle back into yourself. */
    public static final String POUR = "rainsxpbottles.bottle.pour";

    /** Holding a siphon bottle down to pull loose orbs in. */
    public static final String SIPHON = "rainsxpbottles.siphon.use";

    /** Opening the module's own screen. */
    public static final String MENU = "rainsxpbottles.menu";

    /** Conjuring a siphon bottle for somebody. */
    public static final String GIVE = "rainsxpbottles.give";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(FILL, "Draw your own experience into a bottle",
                        PermissionDefault.TRUE),
                new Permission(POUR, "Pour a bottle of experience back into yourself",
                        PermissionDefault.TRUE),
                new Permission(SIPHON, "Use a siphon bottle to pull loose experience orbs in",
                        PermissionDefault.TRUE),
                new Permission(MENU, "Open the XP bottle screen", PermissionDefault.TRUE),
                new Permission(GIVE, "Give somebody a siphon bottle", PermissionDefault.OP));
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
