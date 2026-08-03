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
public final class ZonesMenu extends PaginatedMenu<NoClaimZone> implements IClaimScreen {

    private final ClaimServices services;

    public ZonesMenu(ClaimServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        // Short enough not to be clipped at all. The button that opens this still reads "Where nobody may
        // claim" — a title has 146 pixels and a lore line has the width of the screen.
        return Component.text("No-claim zones");
    }

    @Override
    protected List<NoClaimZone> entries() {
        return new ArrayList<>(services.zones().all());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Anywhere goes",
                "<gray>Nowhere on this server is off limits yet.",
                "<dark_gray>use the blaze rod below to mark somewhere out");
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
    protected void decorate() {
        // The stick itself, rather than an instruction to go and type something. Telling an admin who is
        // already looking at the list to close it and type /claimadmin zone is asking them to leave the place
        // that raised the question — and the command does exactly this, so the menu may as well.
        toolbar(4, Icons.of(Material.BLAZE_ROD, "<red>Mark somewhere out",
                        "<gray>Puts the no-claim rod in your hand and",
                        "<gray>starts a selection where you stand.",
                        "",
                        "<dark_gray>right click the corners, shift + right click to finish"),
                click -> {
                    if (!services.rights().isServerAdmin(viewer)) {
                        services.messages().send(viewer, "error.no-permission");
                        return;
                    }
                    viewer.closeInventory();
                    services.selectionFlow().begin(viewer,
                            de.raindancer.modules.claims.selection.Selection.Mode.RECTANGLE,
                            de.raindancer.modules.claims.selection.Selection.Purpose.NO_CLAIM_ZONE,
                            null, null, null);
                });
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
