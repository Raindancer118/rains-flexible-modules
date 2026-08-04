package de.raindancer.modules.warp.rules;

import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.util.PermissionNodes;

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

    @Override
    public String describe() {
        return "who may use, see and change a warp";
    }
}
