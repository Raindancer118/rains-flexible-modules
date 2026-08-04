package de.raindancer.modules.names.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * Telling the server which permissions exist, and who has them without being granted anything.
 *
 * <h2>Why this is not optional</h2>
 * An unregistered permission resolves against {@code Permission.DEFAULT_PERMISSION}, which is
 * {@code OP} — so an unregistered {@link #USE} would refuse the manual to every ordinary player, on a
 * server where the only person who could test it is the one person for whom it works.
 *
 * <p>And the correction has its own trap: {@link PermissionDefault#FALSE} does not mean "not by
 * default", it means <b>nobody, the server owner included</b>. Neither mistake announces itself, which
 * is why both defaults below are asserted in a test rather than chosen in passing.
 *
 * <p>Declared here rather than in a {@code paper-plugin.yml} because a module does not have one of its
 * own — it may be hosted inside somebody else's plugin — and registering programmatically is the only
 * way that works in both arrangements.
 *
 * <h2>Why the nodes still say {@code colourednames}</h2>
 * Because a permission node is a string somebody has typed into LuckPerms. Renaming these to
 * {@code names.*} would cost every upgrading server the grants it has already made, silently, and buy
 * nothing but tidiness. Crafting needs no permission on any server, which is the only part of this most
 * owners will ever care about.
 */
public final class PermissionNodes {

    /** Reading the manual. Everybody, because crafting needs no permission either. */
    public static final String USE = "colourednames.use";

    /** Re-reading the config. Operators. */
    public static final String RELOAD = "colourednames.reload";

    private PermissionNodes() {
    }

    /**
     * Every node this module understands, with its default.
     *
     * <p>Built rather than registered, so the defaults can be asserted without a server.
     *
     * <pre>
     *   TRUE    → everybody
     *   OP      → operators
     *   NOT_OP  → everybody except operators
     *   FALSE   → nobody, operators included
     * </pre>
     */
    public static List<Permission> declared() {
        return List.of(
                new Permission(USE,
                        "Read the manual: what each dye does on this server, and how to craft it",
                        PermissionDefault.TRUE),
                new Permission(RELOAD,
                        "Re-read the palette and the settings from disk",
                        PermissionDefault.OP));
    }

    /**
     * Registers the lot.
     *
     * <p>Idempotent: a node already registered — by a reload, or by a host that has this module twice —
     * is left as it is rather than throwing, because a module that refuses to start over a duplicate
     * permission is worse than one that shares.
     *
     * @return how many were newly registered
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
                // Registered between the check and the add, or by another copy of this module. Either
                // way it exists, which is all this method wanted.
            }
        }
        return added;
    }
}
