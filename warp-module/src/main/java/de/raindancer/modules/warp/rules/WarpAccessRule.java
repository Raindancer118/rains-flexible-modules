package de.raindancer.modules.warp.rules;

import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.util.PermissionNodes;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who may use which warp, who may see it, and who may change it.
 *
 * <h2>Why it takes a predicate rather than a player</h2>
 * Because this is the security decision of the module, and a rule that needed a running server would
 * be one checked by hand on a test server. "The staff warps are listed for everybody" is not a thing
 * to find out that way. A predicate is {@code Player::hasPermission} in production and three lines
 * in a test.
 *
 * <h2>The two decisions worth knowing about</h2>
 * <ul>
 *   <li><b>An admin reaches everything.</b> Somebody has to be able to go and look at a broken warp,
 *       and an admin who cannot reach the one they are fixing fixes it by deleting it.</li>
 *   <li><b>What you may not use, you are not shown.</b> This is the module's one deliberate
 *       exception to "greyed, never hidden" — greying a staff warp tells every player on the server
 *       that there is a warp called {@code staffroom}, which is the half of the secret that
 *       matters. {@link #maySee} and {@link #mayUse} therefore agree exactly, so no button in the
 *       menu can refuse after the click.</li>
 * </ul>
 */
public final class WarpAccessRule implements IWarpRule {

    /**
     * Whether this player may use a warp with this access.
     *
     * @param access         null is refused, never opened: a warp whose access could not be read is
     *                       one nobody should be sent to on a guess
     * @param hasPermission  how to ask; null is nobody
     */
    public boolean mayUse(WarpAccess access, Predicate<String> hasPermission) {
        if (hasPermission == null || access == null) {
            return false;
        }
        if (hasPermission.test(PermissionNodes.MANAGE)) {
            return true;
        }
        // The node that switches warping off for a group. Without it a server that took
        // rainswarps.warp.use away would find every public warp still working.
        return hasPermission.test(PermissionNodes.USE) && access.allows(hasPermission);
    }

    /**
     * Whether this player is shown it at all.
     *
     * <p>Deliberately the same answer as {@link #mayUse}. Two rules that could disagree is a menu
     * offering something and then refusing it, which is a button people press four more times.
     */
    public boolean maySee(WarpAccess access, Predicate<String> hasPermission) {
        return mayUse(access, hasPermission);
    }

    /**
     * Whether this player may make, move, retag or delete a warp.
     *
     * <p>Holding the staff node is being allowed <em>into</em> the staff warps. It is not being
     * allowed to move them.
     */
    public boolean mayManage(Predicate<String> hasPermission) {
        return hasPermission != null && hasPermission.test(PermissionNodes.MANAGE);
    }

    // ------------------------------------------------------------------------ owned warps

    /**
     * Whether this player may use a warp, knowing who owns it.
     *
     * <p>Its owner always may, as long as they may warp at all; a {@link WarpAccess#PRIVATE} warp is
     * for the owner and {@code members} only. Everything else is {@link #mayUse(WarpAccess, Predicate)}.
     */
    public boolean mayUse(WarpAccess access, Predicate<String> hasPermission, UUID who, UUID owner,
                          Set<UUID> members) {
        if (hasPermission == null || access == null) {
            return false;
        }
        if (hasPermission.test(PermissionNodes.MANAGE)) {
            return true;
        }
        if (!hasPermission.test(PermissionNodes.USE)) {
            return false;
        }
        if (who != null && who.equals(owner)) {
            return true;
        }
        if (access instanceof WarpAccess.Private) {
            return who != null && members != null && members.contains(who);
        }
        return access.allows(hasPermission);
    }

    /** The same answer, for whether it is listed — see {@link #maySee}. */
    public boolean maySee(WarpAccess access, Predicate<String> hasPermission, UUID who, UUID owner,
                          Set<UUID> members) {
        return mayUse(access, hasPermission, who, owner, members);
    }

    /** Whether this player may move, rename, re-icon, open up or delete one warp: its owner, or staff. */
    public boolean mayChange(Predicate<String> hasPermission, UUID who, UUID owner) {
        return mayManage(hasPermission) || (who != null && who.equals(owner));
    }

    /**
     * Which access an owner may put on their own warp: everybody, or private. The staff warps and the
     * permission nodes are the server's own groups, and a player choosing them is a player deciding who
     * counts as staff.
     */
    public boolean mayChooseAccess(WarpAccess wanted, Predicate<String> hasPermission) {
        return mayManage(hasPermission) || wanted instanceof WarpAccess.Everyone
                || wanted instanceof WarpAccess.Private;
    }

    /** Handing a warp to somebody else — a decision about other people's warps, so the staff's. */
    public boolean mayGive(Predicate<String> hasPermission) {
        return mayManage(hasPermission);
    }

    /** Whether this player may set a new warp at all: staff, the create node, or a token in hand. */
    public boolean mayCreate(Predicate<String> hasPermission, boolean withAToken) {
        return withAToken || mayManage(hasPermission)
                || (hasPermission != null && hasPermission.test(PermissionNodes.CREATE));
    }

    /** Whether one more of their own fits: under the limit, or paid for with a token; staff have none. */
    public boolean hasRoomForOwn(int owned, int limit, Predicate<String> hasPermission, boolean withAToken) {
        return withAToken || mayManage(hasPermission) || owned < limit;
    }

    /** Setting a name that is taken replaces that warp — only for its owner, or staff. */
    public boolean mayReplace(Predicate<String> hasPermission, UUID who, UUID ownerOfTheExisting) {
        return mayChange(hasPermission, who, ownerOfTheExisting);
    }

    @Override
    public String describe() {
        return "who may use, see and change a warp";
    }
}
