package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.NoClaimZone;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.NoClaimZone;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The areas nobody may claim — spawn, a market, an event arena. */
public final class ZonesMenu extends PaginatedMenu<NoClaimZone> {

    private final ClaimServices services;

    public ZonesMenu(ClaimServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Where nobody may claim");
    }

    @Override
    protected List<NoClaimZone> entries() {
        return new ArrayList<>(services.zones().all());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Anywhere goes",
                "<gray>Mark an area out with <white>/claimadmin zone</white>.");
    }

    @Override
    protected ItemStack icon(NoClaimZone zone) {
        return Icons.of(Material.BARRIER, "<red>" + zone.name(),
                "<gray>" + zone.shape().areaBlocks() + " blocks in <white>" + zone.worldName(),
                "<gray>y " + zone.shape().minY() + " to " + zone.shape().maxY(),
                "",
                "<dark_gray>right click to be shown it",
                "<dark_gray>shift + left click to remove it");
    }

    @Override
    protected void onClick(NoClaimZone zone, InventoryClickEvent event) {
        if (!services.rights().isServerAdmin(viewer)) {
            services.messages().send(viewer, "error.no-permission");
            return;
        }
        if (event.isRightClick()) {
            viewer.closeInventory();
            services.visualizer().showZone(viewer, zone, services.config().visualDurationSeconds());
            return;
        }
        if (!event.isShiftClick()) {
            // Removing a zone is not something to do on a plain click while paging through a list.
            services.messages().send(viewer, "admin.zone-shift-to-remove", "zone", zone.name());
            return;
        }
        services.zones().remove(zone.name());
        services.messages().send(viewer, "admin.zone-removed", "zone", zone.name());
        refresh();
    }
}
