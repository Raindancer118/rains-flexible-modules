package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.Claim;
import de.raindancer.modules.claims.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The people trusted here, as heads.
 *
 * <p>Heads rather than named paper, because a list of players you have to read is a list you scan and a list of
 * faces is a list you recognise — which is the entire reason heads exist in menus. Core draws them, so it is one
 * call rather than the profile-fetching this module used to do for itself.
 *
 * <p>Owners are listed first and marked, because "why can I not remove this person" has an answer on screen when
 * their button says they are an owner.
 */
public final class MembersMenu extends PaginatedMenu<MembersMenu.Entry> {

    /** One row of the list: somebody, and what they are here. */
    record Entry(UUID who, boolean owner, boolean claimAdmin) {
    }

    private final ClaimServices services;
    private final Claim claim;

    public MembersMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Trusted people");
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> rows = new ArrayList<>();
        for (UUID owner : claim.owners()) {
            rows.add(new Entry(owner, true, false));
        }
        claim.members().forEach((who, member) -> rows.add(new Entry(who, false, member.isClaimAdmin())));
        return rows;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nobody else yet",
                "<gray>Trust somebody with <white>/claim trust <name></white>,",
                "<gray>then click them here to say what they may do.");
    }

    @Override
    protected ItemStack icon(Entry entry) {
        List<String> lore = new ArrayList<>();
        if (entry.owner()) {
            lore.add("<gold>An owner");
            lore.add("<gray>Owners may do everything, always.");
        } else {
            int allowed = claim.member(entry.who()).map(member -> member.permissions().size()).orElse(0);
            lore.add("<gray>" + allowed + " permission(s)");
            if (entry.claimAdmin()) {
                lore.add("<aqua>Also helps manage the claim");
            }
            lore.add("");
            lore.add("<dark_gray>click to change what they may do");
        }
        return Icons.head(entry.who(),
                (entry.owner() ? "<gold>" : "<white>") + services.names().nameOfOwner(entry.who()), lore);
    }

    @Override
    protected void onClick(Entry entry, InventoryClickEvent event) {
        if (entry.owner()) {
            // Nothing to change: an owner holds everything by definition, so a permission screen for one
            // would be seventeen buttons that all say yes and none of which do anything.
            services.messages().send(viewer, "claim.owner-holds-everything");
            return;
        }
        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_PERMISSIONS)) {
            services.messages().send(viewer, "error.no-claim-permission");
            return;
        }
        new MemberMenu(services, viewer, claim, this, entry.who()).open();
    }
}
