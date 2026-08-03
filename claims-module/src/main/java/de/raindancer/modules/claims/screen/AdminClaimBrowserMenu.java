package de.raindancer.modules.claims.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.choose.PlayerEntry;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.selection.Selection;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Every claim on the server, not only the ones the viewer happens to own or be trusted on.
 *
 * <h2>Why this exists next to {@link ClaimListMenu}</h2>
 * That screen answers "what can I get into" and reads {@link ClaimServices#claims()} through
 * {@code accessibleBy}, which is exactly wrong for an admin trying to find somebody else's claim — an admin who
 * is not an owner or a trusted member of the claim they are looking for gets nothing back at all. This reads
 * every claim there is, because finding one an admin does not already know about is the entire point of a
 * browser rather than a list.
 *
 * <h2>Reachable only through the gate that matters</h2>
 * Nothing here is linked from anywhere a non-admin can reach: {@link AdminMenu} is opened by
 * {@code /claimadmin}, and that command already refuses anybody without {@code rec.admin}. The
 * {@link de.raindancer.core.world.protection.Land#isServerAdmin} check on every click is defence in depth for
 * the case that permission changes under a player already looking at this window — not the only gate.
 */
public final class AdminClaimBrowserMenu extends PaginatedMenu<Claim> implements IClaimScreen {

    /** How the list is ordered. Cycled from the toolbar, one click at a time. */
    private enum Sort {
        AREA("largest first"),
        NAME("by name"),
        AGE("newest first"),
        OWNER("by owner");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final ClaimServices services;
    private final UUID ownerFilter;
    private Sort sort = Sort.AREA;

    public AdminClaimBrowserMenu(ClaimServices services, Player viewer, Menu parent) {
        this(services, viewer, parent, null);
    }

    public AdminClaimBrowserMenu(ClaimServices services, Player viewer, Menu parent, UUID ownerFilter) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.ownerFilter = ownerFilter;
    }

    @Override
    protected Component title() {
        return Component.text(ownerFilter == null
                ? "Claims — every claim on the server"
                : "Claims — " + services.names().nameOfOwner(ownerFilter) + "'s");
    }

    @Override
    public String breadcrumb() {
        return "the claim browser";
    }

    @Override
    protected List<Claim> entries() {
        List<Claim> claims = ownerFilter == null
                ? new ArrayList<>(services.claims().all())
                : new ArrayList<>(services.claims().ownedBy(ownerFilter));
        Comparator<Claim> comparator = switch (sort) {
            case AREA -> Comparator.<Claim>comparingLong(claim -> claim.shape().areaBlocks()).reversed();
            case NAME -> Comparator.comparing(Claim::name, String.CASE_INSENSITIVE_ORDER);
            case AGE -> Comparator.<Claim>comparingLong(Claim::createdAt).reversed();
            case OWNER -> Comparator.comparing(
                    (Claim claim) -> services.names().nameOfOwner(claim.primaryOwner()),
                    String.CASE_INSENSITIVE_ORDER);
        };
        claims.sort(comparator);
        return claims;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.STRUCTURE_VOID, "<gray>No claims found",
                ownerFilter == null ? "<dark_gray>nobody has claimed anything yet"
                        : "<dark_gray>that player owns nothing");
    }

    @Override
    protected ItemStack icon(Claim claim) {
        ItemStack icon = claim.iconOr(true);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Owned by <white>" + services.names().allOwners(claim));
        lore.add("<gray>" + claim.shape().areaBlocks() + " blocks in <white>" + claim.worldName());
        lore.add("<dark_gray>y " + claim.shape().minY() + " to " + claim.shape().maxY());
        lore.add("<dark_gray>" + claim.members().size() + " trusted, " + claim.bans().size() + " barred");
        lore.add("");
        lore.add("<yellow>click <dark_gray>manage as admin");
        lore.add("<yellow>shift + click <dark_gray>teleport there");
        lore.add("<yellow>right click <dark_gray>show the outline");
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Icons.name("<white>" + services.names().listed(claim)));
            meta.lore(lore.stream().map(Icons::loreLine).toList());
            icon.setItemMeta(meta);
        }
        return icon;
    }

    @Override
    protected void onClick(Claim claim, InventoryClickEvent event) {
        if (!services.rights().isServerAdmin(viewer)) {
            services.messages().send(viewer, "error.no-permission");
            return;
        }
        if (event.isRightClick()) {
            viewer.closeInventory();
            services.visualizer().showClaim(viewer, claim, services.config().visualDurationSeconds());
            return;
        }
        if (event.isShiftClick()) {
            teleportTo(claim);
            return;
        }
        new AdminClaimMenu(services, viewer, this, claim).open();
    }

    /**
     * Teleports through {@link de.raindancer.core.world.safety.Safety} rather than to the stored centre
     * outright — a claim's middle block may have been mined out or built over since it was drawn, and
     * dropping an admin into it blind is exactly the bug that seam exists to stop.
     */
    private void teleportTo(Claim claim) {
        World world = services.server().getWorld(claim.worldId());
        if (world == null) {
            services.messages().send(viewer, "error.world-missing", "world", claim.worldName());
            return;
        }
        viewer.closeInventory();
        var centre = claim.shape().centre();
        int middleY = Math.floorDiv(claim.shape().minY() + claim.shape().maxY(), 2);
        Spot around = new Spot(world.getName(), centre.x(), middleY, centre.z());
        services.core().safety().findSafe(around, 12).thenAccept(found ->
                Scheduling.entity(services.plugin(), viewer, () -> found.ifPresentOrElse(
                        spot -> {
                            World target = Bukkit.getWorld(spot.world());
                            if (target == null) {
                                return;
                            }
                            viewer.teleportAsync(new Location(target, spot.centreX(), spot.y(), spot.centreZ()));
                            services.visualizer().showClaim(viewer, claim, services.config().visualDurationSeconds());
                        },
                        () -> services.messages().send(viewer, "admin.teleport-unsafe", "claim", claim.name()))));
    }

    @Override
    protected void render() {
        super.render();

        toolbar(2, Icons.of(Material.HOPPER, "<yellow>Sort: <white>" + sort.label,
                "<gray>Click to cycle the order."), event -> {
            sort = sort.next();
            refresh();
        });

        toolbar(6, Icons.head(ownerFilter, ownerFilter == null
                        ? "<yellow>Filter by owner"
                        : "<yellow>Showing only " + services.names().nameOfOwner(ownerFilter),
                ownerFilter == null
                        ? "<dark_gray>click to choose"
                        : "<gray>Click to see every claim again."),
                event -> {
            if (ownerFilter != null) {
                new AdminClaimBrowserMenu(services, viewer, parent(), null).open();
                return;
            }
            askForOwner();
        });
    }

    /** Picks who to filter by with a click — every player the server knows of is a fair pick here. */
    private void askForOwner() {
        new PlayerChooser(viewer, services.brand(), this, "Filter by owner",
                List.of(), person ->
                        new AdminClaimBrowserMenu(services, viewer, parent(), person.id()).open())
                .open();
    }

    /** Admin-only actions on one claim, on top of everything the owner menu offers. */
    public static final class AdminClaimMenu extends ClaimScreen {

        public AdminClaimMenu(ClaimServices services, Player viewer, Menu parent, Claim claim) {
            super(services, viewer, claim, parent);
        }

        @Override
        protected Component title() {
            return Component.text(claim().name() + " — staff view");
        }

        @Override
        public String breadcrumb() {
            return claim().name();
        }

        @Override
        protected void render() {
            Claim claim = claim();
            set(MenuLayout.HEADER_SUBJECT, Icons.of(claim.iconMaterial(true),
                    "<white>" + services().names().listed(claim),
                    "<gray>" + services().names().allOwners(claim)));

            band(MenuLayout.WHO, 1, Icons.of(Material.KNOWLEDGE_BOOK, "<aqua>Full details",
                            "<gray>Everything about this claim, regardless of who owns it."),
                    click -> new ClaimInfoMenu(services(), viewer, claim, this).open());

            band(MenuLayout.WHO, 3, Icons.of(Material.CHEST, "<aqua>Open the owner menu",
                            "<gray>Everything the owner can change,",
                            "<gray>with your admin rights."),
                    click -> new ClaimMenu(services(), viewer, claim, this).open());

            band(MenuLayout.WHO, 5, Icons.of(Material.GOLDEN_HOE, "<aqua>Reshape this claim",
                            "<gray>Hands you the selection stick.",
                            "<gray>The usual size and overlap limits do not apply —",
                            "<gray>this is for a claim that is already out of bounds."),
                    click -> {
                        if (!services().rights().isServerAdmin(viewer)) {
                            services().messages().send(viewer, "error.no-permission");
                            return;
                        }
                        viewer.closeInventory();
                        services().selectionFlow().begin(viewer, Selection.Mode.RECTANGLE,
                                Selection.Purpose.ADMIN_RESHAPE, null, claim, null);
                    });

            band(MenuLayout.WHO, 7, Icons.of(Material.ENDER_PEARL, "<aqua>Teleport to the centre",
                            "<gray>Checked for safety first."),
                    click -> teleportTo(claim));

            band(MenuLayout.RULES, 4, Icons.of(Material.BEACON, "<aqua>Transfer ownership",
                            "<gray>Replaces every current owner.",
                            "<dark_gray>click to choose the new owner"),
                    click -> {
                        if (!services().rights().isServerAdmin(viewer)) {
                            services().messages().send(viewer, "error.no-permission");
                            return;
                        }
                        // Offering a current owner back as "the new owner" would be a no-op, so they are
                        // excluded rather than picked and then refused.
                        new PlayerChooser(viewer, services().brand(), this, "Transfer ownership",
                                new ArrayList<>(claim.owners()),
                                person -> onTransferChosen(claim, person)).open();
                    });

            danger(Icons.of(Material.TNT, "<red><bold>Delete this claim",
                            "<gray>No refund is paid to the owner."),
                    click -> new ConfirmScreen(services(), viewer, claim, this,
                            "<red>Delete \"" + claim.name() + "\"?",
                            List.of("<gray>Owned by " + services().names().allOwners(claim),
                                    "<gray>" + claim.shape().areaBlocks() + " blocks",
                                    "<red>The land becomes unprotected at once."),
                            () -> {
                                services().claimService().delete(claim, null);
                                services().messages().send(viewer, "admin.claim-deleted", "claim", claim.name());
                                if (parent() != null) {
                                    parent().open();
                                }
                            }).open());
        }

        private void onTransferChosen(Claim claim, PlayerEntry person) {
            new ConfirmScreen(services(), viewer, claim, this,
                    "<gold>Hand \"" + claim.name() + "\" to " + person.name() + "?",
                    List.of("<gray>All current owners lose the claim."),
                    () -> {
                        List<UUID> previous = new ArrayList<>(claim.owners());
                        claim.addOwner(person.id());
                        previous.forEach(claim::removeOwner);
                        services().claims().reindex(claim);
                        services().claimService().saveAsync(claim);
                        services().messages().send(viewer, "admin.ownership-transferred",
                                "claim", claim.name(), "player", person.name());
                        open();
                    }).open();
        }

        private void teleportTo(Claim claim) {
            World world = services().server().getWorld(claim.worldId());
            if (world == null) {
                services().messages().send(viewer, "error.world-missing", "world", claim.worldName());
                return;
            }
            viewer.closeInventory();
            var centre = claim.shape().centre();
            int middleY = Math.floorDiv(claim.shape().minY() + claim.shape().maxY(), 2);
            Spot around = new Spot(world.getName(), centre.x(), middleY, centre.z());
            services().core().safety().findSafe(around, 12).thenAccept(found ->
                    Scheduling.entity(services().plugin(), viewer, () -> found.ifPresentOrElse(
                            spot -> {
                                World target = Bukkit.getWorld(spot.world());
                                if (target == null) {
                                    return;
                                }
                                viewer.teleportAsync(
                                        new Location(target, spot.centreX(), spot.y(), spot.centreZ()));
                                services().visualizer().showClaim(viewer, claim,
                                        services().config().visualDurationSeconds());
                            },
                            () -> services().messages().send(viewer, "admin.teleport-unsafe",
                                    "claim", claim.name()))));
        }
    }
}
