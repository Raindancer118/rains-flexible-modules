package de.raindancer.modules.claims.rules;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import org.bukkit.entity.Player;

/**
 * Who may <em>change</em> a claim, as opposed to who may do things inside one.
 *
 * <p>The distinction is the reason this is here and not in Core. "May build" is world protection and every
 * region plugin has it; "may rename this claim, add a co-owner, redraw its shape or hand out permissions" is
 * about a claim's own management structure, and an arena has no equivalent.
 *
 * <p>What is still Core's: the {@code rec.admin} node itself, so that one server-wide administrator permission
 * governs every kind of protected ground rather than one per plugin.
 */
public final class ClaimRightsRule implements IClaimRule {

    @Override
    public String describe() {
        return "who may change a claim";
    }


    private final Land land;

    public ClaimRightsRule(Land land) {
        this.land = land;
    }

    /** Whether they hold the server-wide administrator permission. */
    public boolean isServerAdmin(Player player) {
        return land.isServerAdmin(player);
    }

    /**
     * Whether this player has switched the protection bypass on.
     *
     * <p>Being an operator is not this by itself — see {@code ClaimPermissions}. An admin who genuinely needs
     * to skip a limit, a cost or a no-claim zone switches this on with {@code /claimadmin bypass} for as long
     * as they are actually working, rather than every operator getting it for free forever.
     */
    public boolean isBypassing(Player player) {
        return land.isBypassing(player);
    }

    /**
     * Whether the player may change the claim in this way.
     *
     * <p>Server admins may. Owners may. Anybody else needs the specific right delegated to them, which is what
     * makes a claim admin useful without making them a second owner.
     */
    public boolean canManage(Claim claim, Player player, ClaimAdminPermission permission) {
        if (claim == null || player == null) {
            return false;
        }
        if (isServerAdmin(player)) {
            return true;
        }
        if (claim.isOwner(player.getUniqueId())) {
            return true;
        }
        return claim.member(player.getUniqueId()).map(member -> member.has(permission)).orElse(false);
    }

    /**
     * Whether they may hand this permission to somebody else.
     *
     * <p>Owners may grant anything. A claim admin may grant only what the owner put on their list — the point of
     * delegating is that it is bounded, or it is not delegation but a second owner. In particular somebody who
     * may manage permissions must not be able to grant themselves the ones they were not given.
     */
    public boolean canGrant(Claim claim, Player player, LandAction action) {
        if (claim == null || player == null) {
            return false;
        }
        if (isServerAdmin(player) || claim.isOwner(player.getUniqueId())) {
            return true;
        }
        return claim.member(player.getUniqueId())
                .map(member -> member.has(ClaimAdminPermission.MANAGE_PERMISSIONS)
                        && member.grantablePermissions().contains(action))
                .orElse(false);
    }

    /**
     * For the things that cannot be undone — deleting a claim, handing it over.
     *
     * <p>Deliberately not available to a claim admin however many rights they hold. Somebody trusted to manage
     * the members is not thereby trusted to delete the place.
     */
    public boolean isOwnerOrServerAdmin(Claim claim, Player player) {
        if (claim == null || player == null) {
            return false;
        }
        return isServerAdmin(player) || claim.isOwner(player.getUniqueId());
    }
}
