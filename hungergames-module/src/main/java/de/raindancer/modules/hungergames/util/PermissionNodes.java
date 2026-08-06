package de.raindancer.modules.hungergames.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody.
 *
 * <p>Registered programmatically rather than in a descriptor, because a module may be hosted inside another
 * plugin and have no descriptor of its own. Registering is idempotent — two copies of the module on one
 * server is a real state — and it happens before anything asks, since an unregistered node resolves to
 * "operators only".
 *
 * <h2>Why there are three nodes and not one</h2>
 * A tournament is run by two different kinds of person and the difference matters on the night.
 *
 * <p>An <b>admin</b> owns the server: they build the arena, edit the loot, change the settings and can break
 * anything. A <b>gamemaster</b> runs the round: they call the deathmatch, drop supplies, revive somebody the
 * plugin got wrong, and watch from spectator. Those are usually different people, and the gamemaster is often
 * somebody trusted for one evening. Collapsing them into one node means the only way to let a guest call a
 * supply drop is to give them the button that regenerates the arena mid-round.
 *
 * <p>{@link #PROTECTION_BYPASS} is separate again, and deliberately not implied by either. It turns off the
 * rule that stops blocks being broken where they must not be, and an admin who has it permanently is an admin
 * who eventually mines the cornucopia by accident while everybody watches. It is a node to be granted for the
 * ten minutes somebody is fixing something.
 *
 * <h2>Why {@code /allow} does not grant OP</h2>
 * The version this replaced gave operator status to whoever was let into a round, because that was the
 * quickest way to make the run-up commands work. Everybody who ever played had OP afterwards. These three
 * nodes exist so that the run-up needs none.
 */
public final class PermissionNodes {

    /** Owns the server: the arena, the loot, the settings, the whole admin suite. */
    public static final String ADMIN = "hungergames.admin";

    /** Runs the round: the deathmatch, supply drops, revives, spectating. Not the settings. */
    public static final String GAMEMASTER = "hungergames.gamemaster";

    /**
     * Ignores the protection matrix.
     *
     * <p>For the ten minutes somebody is fixing something, not for a staff group. See the class note.
     */
    public static final String PROTECTION_BYPASS = "hungergames.protection.bypass";

    private PermissionNodes() {
    }

    /** Whether they own the server. */
    public static boolean isAdmin(Permissible who) {
        return who != null && who.hasPermission(ADMIN);
    }

    /** Whether they are running this round. */
    public static boolean isGamemaster(Permissible who) {
        return who != null && who.hasPermission(GAMEMASTER);
    }

    /**
     * Whether the admin suite opens for them at all.
     *
     * <p>Either node. The suite itself greys what a gamemaster may not press rather than showing them a
     * different menu — a page whose shape depends on who is looking is a page nobody can be talked through
     * over voice while forty people wait.
     */
    public static boolean mayOpenTheAdminSuite(Permissible who) {
        return isAdmin(who) || isGamemaster(who);
    }

    /** Whether the protection matrix lets them past. */
    public static boolean bypassesProtection(Permissible who) {
        return who != null && who.hasPermission(PROTECTION_BYPASS);
    }

    /**
     * The nodes, for a permissions plugin's list of known ones.
     *
     * <p>All three default to operators. That is the right way round here and the opposite of the farm
     * worlds module: a farm world nobody can enter is not one, whereas a tournament every player can call a
     * deathmatch in is not one either.
     */
    public static List<Permission> declared() {
        return List.of(
                new Permission(ADMIN,
                        "Build the arena, edit the loot and the settings, and use the whole admin suite",
                        PermissionDefault.OP),
                new Permission(GAMEMASTER,
                        "Run a round: the deathmatch, supply drops, revives and spectating",
                        PermissionDefault.OP),
                new Permission(PROTECTION_BYPASS,
                        "Ignore the protection rules — meant for the minutes somebody is fixing something, "
                                + "not for a staff group",
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
