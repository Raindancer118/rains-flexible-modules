package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Which of a trusted claim admin's own permissions they may hand on to somebody else — the write side of
 * {@code ClaimRightsRule.canGrant}.
 *
 * <p>{@code ClaimMember.grantablePermissions()} was read by {@code ClaimRightsRule.canGrant} from the
 * moment delegation existed, but nothing besides {@code ClaimStorage}'s loader ever wrote to it: a claim
 * admin's grantable list could only ever be whatever a save file happened to already contain.
 *
 * <p>Owner only, deliberately, and not through {@code MANAGE_PERMISSIONS} the way the plain grid is:
 * editing what somebody may grant is editing how far their own delegation reaches, and a claim admin who
 * could widen their own grantable list would simply be handing themselves a wider one at one remove.
 */
public final class MemberGrantableMenu extends PermissionGrid {

    private final UUID subject;

    public MemberGrantableMenu(ClaimServices services, Player viewer, Claim claim, Menu parent, UUID subject) {
        super(services, viewer, claim, parent);
        this.subject = subject;
    }

    @Override
    protected Component title() {
        return Component.text(services().names().nameOfOwner(subject) + " — may grant");
    }

    @Override
    protected boolean holds(LandAction action) {
        return claim().member(subject)
                .map(member -> member.grantablePermissions().contains(action)).orElse(false);
    }

    @Override
    protected void set(LandAction action, boolean allowed) {
        var member = claim().memberOrCreate(subject);
        if (allowed) {
            member.grantablePermissions().add(action);
        } else {
            member.grantablePermissions().remove(action);
        }
    }

    @Override
    protected boolean mayChange(LandAction action) {
        return services().rights().isOwnerOrServerAdmin(claim(), viewer)
                && !subject.equals(viewer.getUniqueId());
    }

    @Override
    protected String refusal(LandAction action) {
        if (subject.equals(viewer.getUniqueId())) {
            return "Your own entry — somebody else's to change";
        }
        return "The owner's to change";
    }
}
