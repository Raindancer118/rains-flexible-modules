package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
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

    /**
     * Lets them know, if they are online to be told.
     *
     * <p>Somebody trusted with a permission finds out by trying it otherwise — walking into a door that
     * still refuses them because the grant has not reached the client yet is indistinguishable from a
     * grant that never happened at all.
     */
    @Override
    protected void afterChange(LandAction action, boolean allowed) {
        Player theirs = services().server().getPlayer(subject);
        if (theirs == null) {
            return;
        }
        services().messages().send(theirs, allowed
                        ? "notify.permission-granted" : "notify.permission-revoked",
                "action", services().messages().raw(action.nameKey()),
                "claim", claim().name(),
                "player", viewer.getName());
    }

    /**
     * Two doors this page did not have: what this person may change <em>about</em> the claim, and which of
     * their own permissions they may hand on to somebody else.
     *
     * <p>Both are owner only, on purpose. The grid above is delegable through {@code MANAGE_PERMISSIONS}
     * because handing out "may open doors" is what a claim admin exists for; deciding who else gets to be
     * a claim admin, or how far somebody's own delegation reaches, is a step up from that — the same reason
     * {@link MembersMenu} refuses to touch an owner's own entry at all.
     */
    @Override
    protected void render() {
        super.render();
        boolean ownerOnly = services().rights().isOwnerOrServerAdmin(claim(), viewer)
                && !subject.equals(viewer.getUniqueId());
        int adminCount = claim().member(subject).map(member -> member.adminPermissions().size()).orElse(0);
        int grantableCount = claim().member(subject)
                .map(member -> member.grantablePermissions().size()).orElse(0);

        band(MenuLayout.RULES, 2, ownerOnly,
                Icons.of(Material.BEACON, "<aqua>Claim admin rights",
                        "<gray>What this person may change about the claim itself.",
                        "<dark_gray>" + adminCount + " granted"),
                "The owner's to change",
                click -> new MemberAdminMenu(services(), viewer, claim(), this, subject).open());

        band(MenuLayout.RULES, 6, ownerOnly,
                Icons.of(Material.WRITABLE_BOOK, "<aqua>What they may grant",
                        "<gray>Which of their permissions they may hand on to somebody else.",
                        "<dark_gray>" + grantableCount + " grantable"),
                "The owner's to change",
                click -> new MemberGrantableMenu(services(), viewer, claim(), this, subject).open());
    }
}
