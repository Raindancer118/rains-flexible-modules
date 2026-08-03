package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * What one trusted person may change <em>about</em> the claim — as opposed to {@link MemberMenu}'s grid,
 * which is what they may do <em>inside</em> it.
 *
 * <p>{@link ClaimAdminPermission#has} was read by {@link de.raindancer.modules.claims.rules.ClaimRightsRule}
 * ever since a claim admin was a concept at all, but nothing outside {@code ClaimStorage}'s loader ever
 * wrote one — a claim admin's rights, once set up by hand-editing a save file, could be read and enforced
 * but never granted or taken away in the game. This is that route.
 *
 * <p>Owner only, and not delegable through {@code MANAGE_PERMISSIONS}: deciding who else gets to manage the
 * claim is a step up from deciding what one particular door opens for, which is why {@link MemberMenu}
 * gates the button that opens this the same way it gates {@link MemberGrantableMenu}'s.
 */
public final class MemberAdminMenu extends ClaimScreen {

    private final UUID subject;

    public MemberAdminMenu(ClaimServices services, Player viewer, Claim claim, Menu parent, UUID subject) {
        super(services, viewer, claim, parent);
        this.subject = subject;
    }

    @Override
    protected Component title() {
        return Component.text(services().names().nameOfOwner(subject) + " — admin rights");
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>Click a right to turn it on or off.",
                "",
                "<green>Green</green> <dark_gray>·</dark_gray> <gray>granted",
                "<red>Red</red> <dark_gray>·</dark_gray> <gray>not granted",
                "<dark_gray>Owner only — a claim admin cannot promote themselves or anybody else.");
    }

    @Override
    protected void render() {
        boolean mayChange = services().rights().isOwnerOrServerAdmin(claim(), viewer)
                && !subject.equals(viewer.getUniqueId());
        var member = claim().member(subject);
        ClaimAdminPermission[] rights = ClaimAdminPermission.values();
        for (int at = 0; at < rights.length; at++) {
            ClaimAdminPermission right = rights[at];
            int row = at / 9;
            int column = at % 9;
            boolean on = member.map(holder -> holder.has(right)).orElse(false);
            ItemStack icon = Icons.of(right.icon(),
                    (on ? "<green>" : "<red>") + right.displayName(),
                    "<gray>" + right.description(),
                    "",
                    on ? "<green>✔ granted" : "<red>✘ not granted",
                    "<dark_gray>click to change");
            if (mayChange) {
                cell(row, column, icon, click -> {
                    var editable = claim().memberOrCreate(subject);
                    if (on) {
                        editable.adminPermissions().remove(right);
                    } else {
                        editable.adminPermissions().add(right);
                    }
                    claim().markDirty();
                    services().claimService().saveAsync(claim());
                    refresh();
                });
            } else {
                String reason = subject.equals(viewer.getUniqueId())
                        ? "Your own entry — somebody else's to change"
                        : "The owner's to change";
                cell(row, column, Icons.locked(icon, reason), click -> {
                });
            }
        }
    }
}
