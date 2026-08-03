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

/**
 * What the claim is holding for its owners: entry fees taken, fence blocks reclaimed, resize refunds.
 *
 * <p>Refunds go here rather than into an inventory, and that is deliberate: a resize refund paid straight into a
 * full inventory falls on the floor, and one paid to an offline co-owner goes nowhere at all.
 *
 * <p>Click to take one stack. Not "take everything" — somebody clearing a bank of forty stacks into a full
 * inventory would drop most of it, and a button that loses items is worse than four clicks.
 */
public final class BankMenu extends PaginatedMenu<ItemStack> {

    private final ClaimServices services;
    private final Claim claim;

    public BankMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Bank");
    }

    @Override
    protected List<ItemStack> entries() {
        return new ArrayList<>(claim.bank().items());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.ENDER_CHEST, "<gray>Nothing in it",
                "<gray>Entry fees, reclaimed fence blocks and",
                "<gray>resize refunds all end up here.");
    }

    @Override
    protected ItemStack icon(ItemStack entry) {
        ItemStack shown = entry.clone();
        return shown;
    }

    @Override
    protected void onClick(ItemStack entry, InventoryClickEvent event) {
        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_BANK)) {
            services.messages().send(viewer, "error.no-claim-permission");
            return;
        }
        int index = claim.bank().items().indexOf(entry);
        if (index < 0) {
            // Somebody else emptied it while this window was open.
            refresh();
            return;
        }
        ItemStack taken = claim.bank().withdrawItem(index);
        if (taken == null) {
            refresh();
            return;
        }
        var leftOver = viewer.getInventory().addItem(taken);
        // Anything that did not fit goes back rather than on the floor: the bank exists so that items are
        // never dropped, and a withdrawal that drops them would defeat the whole point of it.
        leftOver.values().forEach(claim.bank()::returnItem);
        if (!leftOver.isEmpty()) {
            services.messages().send(viewer, "claim.bank-inventory-full");
        }
        services.claimService().saveAsync(claim);
        refresh();
    }

    @Override
    protected void decorate() {
        super.decorate();
        int experience = claim.bank().experiencePoints();
        if (experience > 0) {
            toolbar(4, Icons.of(Material.EXPERIENCE_BOTTLE, "<green>" + experience + " experience",
                            "<gray>Click to take all of it."),
                    click -> {
                        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_BANK)) {
                            services.messages().send(viewer, "error.no-claim-permission");
                            return;
                        }
                        viewer.giveExp(claim.bank().withdrawExperience());
                        services.claimService().saveAsync(claim);
                        refresh();
                    });
        }
    }
}
