package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.choose.PlayerEntry;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
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
public final class MembersMenu extends PaginatedMenu<MembersMenu.Entry> implements IClaimScreen {

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
            if (mayRemoveAsCoOwner(entry.who())) {
                lore.add("");
                lore.add("<dark_gray>click to remove as a co-owner");
            }
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
            // A co-owner may be taken off the way they were put on — a click, not a command — but the
            // permission grid an owner's own entry would otherwise open is still nothing: an owner holds
            // everything by definition, so there would be seventeen buttons that all say yes.
            if (mayRemoveAsCoOwner(entry.who())) {
                if (claim.removeOwner(entry.who())) {
                    services.claims().reindex(claim);
                    services.claimService().saveAsync(claim);
                    services.messages().send(viewer, "claim.owner-removed",
                            "player", services.names().nameOfOwner(entry.who()), "claim", claim.name());
                    refresh();
                } else {
                    services.messages().send(viewer, "claim.owner-cannot-remove",
                            "player", services.names().nameOfOwner(entry.who()));
                }
                return;
            }
            services.messages().send(viewer, "claim.owner-holds-everything");
            return;
        }
        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_PERMISSIONS)) {
            services.messages().send(viewer, "error.no-claim-permission");
            return;
        }
        new MemberMenu(services, viewer, claim, this, entry.who()).open();
    }

    /**
     * Whether this owner entry is one the viewer may take off — a co-owner, never the primary one, and
     * only when the feature and the viewer's own standing both allow it.
     */
    private boolean mayRemoveAsCoOwner(UUID who) {
        return services.features().isOffered(ClaimFeature.CO_OWNERS)
                && services.rights().isOwnerOrServerAdmin(claim, viewer)
                && !who.equals(claim.primaryOwner());
    }

    /**
     * The one route this list was missing: adding a co-owner had a command and nothing to click. Owner
     * only, and deliberately not delegable to a claim admin — see {@code /claim owner} for the same rule.
     */
    @Override
    protected void render() {
        super.render();
        if (services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_MEMBERS)) {
            toolbar(3, Icons.of(Material.OAK_DOOR, "<white>Trust somebody",
                            "<gray>Let them in, without making them an owner.",
                            "<dark_gray>click to choose who"),
                    click -> askToTrust());
        }
        if (services.features().isOffered(ClaimFeature.CO_OWNERS)
                && services.rights().isOwnerOrServerAdmin(claim, viewer)) {
            toolbar(4, Icons.of(Material.GOLDEN_HELMET, "<gold>Add a co-owner",
                            "<gray>Somebody who owns this claim exactly as you do.",
                            "<dark_gray>click to choose who"),
                    click -> askForCoOwner());
        }

        // The click equivalent of /claim transfer — the same owner-or-admin gate as the danger slot on the
        // claim's own front page uses for giving it up entirely, because handing it to somebody else is the
        // same kind of irreversible.
        if (services.rights().isOwnerOrServerAdmin(claim, viewer)) {
            danger(Icons.of(Material.NAME_TAG, "<red>Hand this claim over",
                            "<gray>Somebody else owns it afterward — not you.",
                            "<dark_gray>asks first"),
                    click -> askToTransfer());
        }
    }

    private void askForCoOwner() {
        viewer.closeInventory();
        // Owners already own it, so offering one back as "a co-owner" is a button that would only ever
        // refuse — the chooser leaves them off the list instead of onClick saying no.
        new PlayerChooser(viewer, services.brand(), this, "Add a co-owner",
                new ArrayList<>(claim.owners()), this::onCoOwnerChosen).open();
    }

    private void onCoOwnerChosen(PlayerEntry person) {
        claim.addOwner(person.id());
        services.claims().reindex(claim);
        services.claimService().saveAsync(claim);
        services.messages().send(viewer, "claim.owner-added",
                "player", person.name(), "claim", claim.name());
        open();
    }

    /**
     * The click equivalent of {@code /claim trust <player>} — the door {@link #onClick} could not be,
     * since that one only ever opens the permission grid for somebody already on the list.
     */
    private void askToTrust() {
        viewer.closeInventory();
        // Already an owner or already trusted is already on the list this button would otherwise offer
        // them onto a second time, so the chooser leaves both off rather than onClick saying no.
        List<UUID> alreadyHere = new ArrayList<>(claim.owners());
        alreadyHere.addAll(claim.members().keySet());
        new PlayerChooser(viewer, services.brand(), this, "Trust somebody",
                alreadyHere, this::onTrustChosen).open();
    }

    private void onTrustChosen(PlayerEntry person) {
        claim.memberOrCreate(person.id()).applyDefaultTrust();
        services.claimService().saveAsync(claim);
        services.messages().send(viewer, "claim.trusted",
                "player", person.name(), "claim", claim.name());
        // Told if they are online to be told — otherwise the only way to find out is walking into the
        // claim and noticing the border no longer refuses them. Mirrors /claim trust exactly.
        Player theirs = services.server().getPlayer(person.id());
        if (theirs != null) {
            services.messages().send(theirs, "notify.trusted",
                    "player", viewer.getName(), "claim", claim.name());
        }
        open();
    }

    /**
     * The click equivalent of {@code /claim transfer <player>}. Two steps rather than one, because who is
     * a pick and whether-to-go-through-with-it is a confirmation, and folding both into one button would
     * make the second one a misclick's whole cost rather than a page.
     */
    private void askToTransfer() {
        viewer.closeInventory();
        // Excludes only the viewer: transferring to yourself is not a thing, but handing it to an existing
        // co-owner is — it is what makes them the sole owner instead of one of several.
        new PlayerChooser(viewer, services.brand(), this, "Hand this claim over",
                List.of(viewer.getUniqueId()), this::onTransferChosen).open();
    }

    private void onTransferChosen(PlayerEntry person) {
        new ConfirmScreen(services, viewer, claim, this,
                "<red>Hand " + claim.name() + " over to " + person.name() + "?",
                List.of("<gray>They own it entirely afterward — you do not, not even as a co-owner.",
                        "<gray>This cannot be undone from here."),
                () -> {
                    claim.transferTo(person.id());
                    services.claims().reindex(claim);
                    services.claimService().saveAsync(claim);
                    services.messages().send(viewer, "claim.transferred",
                            "player", person.name(), "claim", claim.name());
                    Player theirs = services.server().getPlayer(person.id());
                    if (theirs != null) {
                        services.messages().send(theirs, "claim.transferred-to-you", "claim", claim.name());
                    }
                    viewer.closeInventory();
                }).open();
    }
}
