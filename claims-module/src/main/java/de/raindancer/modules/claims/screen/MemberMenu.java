package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.UUID;

/** What one trusted person may do here. The same grid as the public one, stored against their name. */
public final class MemberMenu extends PermissionGrid {

    private final UUID subject;

    public MemberMenu(ClaimServices services, Player viewer, Claim claim, Menu parent, UUID subject) {
        super(services, viewer, claim, parent);
        this.subject = subject;
    }

    @Override
    protected Component title() {
        return Component.text(services().names().nameOfOwner(subject));
    }

    @Override
    protected boolean holds(LandAction action) {
        return claim().member(subject).map(member -> member.has(action)).orElse(false);
    }

    @Override
    protected void set(LandAction action, boolean allowed) {
        var member = claim().memberOrCreate(subject);
        if (allowed) {
            member.permissions().add(action);
        } else {
            member.permissions().remove(action);
        }
    }

    @Override
    protected boolean mayChange(LandAction action) {
        return may(ClaimAdminPermission.MANAGE_PERMISSIONS)
                && services().rights().canGrant(claim(), viewer, action)
                // Somebody may not edit their own entry: a claim admin who could would simply grant
                // themselves everything, and delegation that can be widened by its holder is ownership.
                && !subject.equals(viewer.getUniqueId());
    }

    @Override
    protected String refusal(LandAction action) {
        if (subject.equals(viewer.getUniqueId())) {
            return "Your own entry — somebody else's to change";
        }
        return may(ClaimAdminPermission.MANAGE_PERMISSIONS)
                ? "Not one you may hand out"
                : "The owner's to change";
    }
}
