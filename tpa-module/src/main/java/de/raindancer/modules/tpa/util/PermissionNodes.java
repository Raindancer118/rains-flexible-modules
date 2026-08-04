package de.raindancer.modules.tpa.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Every node is the one the old plugin used — as load-bearing as the config paths. An upgrading
 * server has {@code tpa.bypass.warmup} granted to a rank in a permissions plugin, and a renamed node
 * would silently take that away with nothing on screen to explain it.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside
 * another plugin and have no descriptor of its own. Registering is idempotent, and it happens before
 * anything asks — an unregistered node resolves to "operators only", which would refuse teleport
 * requests to every ordinary player.
 */
public final class PermissionNodes {

    /** Asking, answering, and the menu. On by default: this is what the plugin is for. */
    public static final String USE = "tpa.use";

    /** Going back. Also on by default, and it gates whether a death is even recorded. */
    public static final String BACK = "tpa.back";

    /** Skipping the standing-still wait. */
    public static final String BYPASS_WARMUP = "tpa.bypass.warmup";

    /** Skipping both waits — between requests, and between going back. */
    public static final String BYPASS_COOLDOWN = "tpa.bypass.cooldown";

    /**
     * Asking somebody who has requests switched off, or who has blocked you.
     *
     * <p>The one bypass that {@code operators-bypass} deliberately does <b>not</b> cover. The others
     * are about waiting; this one is about somebody else's decision to be left alone, and a config
     * switch that quietly overrode that for every operator would be a different kind of thing
     * entirely. It has to be granted on purpose.
     */
    public static final String BYPASS_TOGGLE = "tpa.bypass.toggle";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(USE,
                        "Ask to teleport to somebody, and answer them",
                        PermissionDefault.TRUE),
                new Permission(BACK,
                        "Go back to where you were, or where you died",
                        PermissionDefault.TRUE),
                new Permission(BYPASS_WARMUP,
                        "Teleport without standing still first",
                        PermissionDefault.FALSE),
                new Permission(BYPASS_COOLDOWN,
                        "Ask again, or go back again, without waiting",
                        PermissionDefault.FALSE),
                new Permission(BYPASS_TOGGLE,
                        "Ask somebody who has requests switched off, or who has blocked you",
                        PermissionDefault.FALSE));
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
