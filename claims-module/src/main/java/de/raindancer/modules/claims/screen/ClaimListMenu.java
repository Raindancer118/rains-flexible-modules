package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every claim this player can get into, theirs first.
 *
 * <p>Own claims before ones they are merely trusted on, because that is the order somebody thinks in — and the old
 * screen listed them by creation time, which meant a player with fifteen claims had theirs scattered among their
 * friends'.
 *
 * <p>Each row shows where it is and how big, so "which of my four homes is the one in the desert" is answerable
 * without opening all four.
 */
public final class ClaimListMenu extends PaginatedMenu<Claim> implements IClaimScreen {

    private final ClaimServices services;

    public ClaimListMenu(ClaimServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Your claims");
    }

    @Override
    protected List<Claim> entries() {
        List<Claim> reachable = new ArrayList<>(services.claims().accessibleBy(viewer.getUniqueId()));
        reachable.sort(Comparator
                .comparing((Claim claim) -> !claim.isOwner(viewer.getUniqueId()))
                .thenComparing(Claim::name, String.CASE_INSENSITIVE_ORDER));
        return reachable;
    }

    @Override
    protected void decorate() {
        super.decorate();

        // Bottom centre, and always — this is the first screen anybody sees, and the people most in need of
        // the manual are exactly the ones with no claim yet, who would otherwise have to own one to find it.
        //
        // That slot belongs to the one destructive button when a page has one. This page has none: there is
        // nothing here to destroy, only claims to open. The framework gives up the page counter for it and
        // keeps the arrows, so a long list still pages — it just stops saying "2 of 3".
        danger(Icons.of(Material.WRITTEN_BOOK, "<white>The manual",
                        "<gray>How claiming works, as a book you keep.",
                        "<dark_gray>also /claim manual"),
                click -> {
                    viewer.closeInventory();
                    services.screens().manual(viewer);
                });
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.STICK, "<gray>You have no claims yet",
                "<gray>Mark two corners with the tool and",
                "<gray>the land between them is yours.",
                "",
                "<yellow>Click to start <dark_gray>· puts the tool in your hand");
    }

    /**
     * Hands them the tool and starts a selection.
     *
     * <p>The icon used to name the command to type and do nothing when clicked, which reads as a broken button
     * rather than as instructions — it is the only thing on the page that is not clickable, and the one a player
     * with no claims tries first. Reported exactly that way.
     *
     * <p>The same route as {@code /claim new}, not a copy of it: the flow revokes any tool they are already
     * holding, gives a fresh one and tells them what to do with it, and none of that is worth having twice.
     */
    @Override
    protected void emptyAction(InventoryClickEvent event) {
        viewer.closeInventory();
        services.selectionFlow().begin(viewer,
                de.raindancer.modules.claims.selection.Selection.Mode.RECTANGLE,
                de.raindancer.modules.claims.selection.Selection.Purpose.NEW_CLAIM,
                null, null, null);
    }

    @Override
    protected ItemStack icon(Claim claim) {
        boolean owner = claim.isOwner(viewer.getUniqueId());
        ItemStack icon = claim.iconOr(owner);
        List<String> lore = new ArrayList<>();
        lore.add(owner ? "<gold>Yours" : "<aqua>Trusted here");
        if (!owner) {
            lore.add("<gray>" + services.names().allOwners(claim) + "'s");
        }
        lore.add("<gray>" + claim.shape().areaBlocks() + " blocks in <white>" + claim.worldName());
        lore.add("");
        lore.add("<dark_gray>click to open · right click to be shown the border");
        var meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Icons.name((owner ? "<gold>" : "<aqua>")
                    + services.names().display(claim, viewer.getUniqueId())));
            meta.lore(lore.stream().map(Icons::loreLine).toList());
            icon.setItemMeta(meta);
        }
        return icon;
    }

    @Override
    protected void onClick(Claim claim, InventoryClickEvent event) {
        if (event.isRightClick()) {
            viewer.closeInventory();
            // Only useful in the world the claim is in; saying so beats an outline nobody can see.
            if (!viewer.getWorld().getUID().equals(claim.worldId())) {
                services.messages().send(viewer, "claim.border-elsewhere",
                        "claim", claim.name(), "world", claim.worldName());
                return;
            }
            services.visualizer().showClaim(viewer, claim, services.config().visualDurationSeconds());
            return;
        }
        new ClaimMenu(services, viewer, claim, this).open();
    }
}
