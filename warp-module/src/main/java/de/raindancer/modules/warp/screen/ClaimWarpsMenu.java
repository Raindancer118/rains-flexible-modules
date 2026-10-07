package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.poi.ClaimWarps;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.modules.warp.WarpServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The claims whose warps this player may use — their owners set them with {@code /claim warp}, and the
 * claim's own rules decide who may arrive. A claim somebody calls home says so.
 */
public final class ClaimWarpsMenu extends PaginatedMenu<Poi> implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final WarpServices services;

    public ClaimWarpsMenu(WarpServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + breadcrumb());
    }

    @Override
    public String breadcrumb() {
        return "Claims' warps";
    }

    @Override
    protected List<Poi> entries() {
        return services.claimWarps().visible(services.arriving(viewer));
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No claim warps for you yet",
                "<gray>A claim's owner sets one by standing",
                "<gray>inside it and typing /claim warp.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        services.messages().send(viewer, "warps.claim.how");
    }

    @Override
    protected ItemStack icon(Poi point) {
        boolean someonesHome = !ClaimWarps.homeOf(point).isEmpty();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + services.claimWarps().ownerName(point) + "'s claim");
        lore.add("<dark_gray>" + point.world() + " " + point.coordinates());
        if (someonesHome) {
            lore.add("<gold>Somebody's main home");
        }
        lore.add("");
        lore.add(services.config().warmup() > 0
                ? "<gray>Click to go. Stand still for " + services.config().warmup() + "s."
                : "<gray>Click to go.");
        lore.add("<dark_gray>/warp claim " + services.claimWarps().typedAs(point, services.arriving(viewer)));
        return Icons.of(someonesHome ? Material.RED_BED : Material.LODESTONE, "<white>" + point.name(), lore);
    }

    @Override
    protected void onClick(Poi point, InventoryClickEvent event) {
        viewer.closeInventory();
        services.travelling().goToPlace(viewer, point.name(), point);
    }

    @Override
    public String describe() {
        return "the claims' warps this player may use";
    }
}
