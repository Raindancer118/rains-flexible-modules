package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.Claim;
import de.raindancer.modules.claims.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * What somebody nobody has trusted may do here.
 *
 * <p>The counterpart to trusting a person: this is the grant everybody gets without being named. Worth its own
 * screen rather than a row on the members page, because it is the setting that decides whether a claim is a
 * private house or a shop — and it is the one people mean when they say a claim is "open".
 */
public final class PublicPermissionsMenu extends PermissionGrid {

    public PublicPermissionsMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text("Everybody else");
    }

    @Override
    protected boolean holds(LandAction action) {
        return claim().publicHas(action);
    }

    @Override
    protected void set(LandAction action, boolean allowed) {
        claim().setPublic(action, allowed);
    }

    @Override
    protected boolean mayChange(LandAction action) {
        return may(ClaimAdminPermission.MANAGE_PUBLIC)
                && services().rights().canGrant(claim(), viewer, action);
    }

    @Override
    protected String refusal(LandAction action) {
        return may(ClaimAdminPermission.MANAGE_PUBLIC)
                ? "Not one you may hand out"
                : "The owner's to change";
    }
}
