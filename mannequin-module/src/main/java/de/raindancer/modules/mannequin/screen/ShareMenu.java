package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.choose.PlayerEntry;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who besides the owner may open and edit this mannequin — the same "trust somebody, without
 * making them an owner" shape {@code claims-module}'s own {@code MembersMenu} already follows, cut
 * down to the one thing a mannequin's trust actually needs: there is no permission grid here,
 * because sharing a mannequin is not a claim with fourteen separate rights to hand out one at a
 * time — either somebody may open and change it, or they may not.
 *
 * <h2>Held by id, not by value</h2>
 * The same reason {@link MannequinEditMenu} is: another window trusting or untrusting somebody
 * while this list is open must not be redrawn over by a stale copy.
 */
public final class ShareMenu extends PaginatedMenu<UUID> implements IMannequinScreen {

    private final MannequinServices services;
    private final String id;

    public ShareMenu(MannequinServices services, Player viewer, Mannequin mannequin, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.id = mannequin.id();
    }

    private Mannequin mannequin() {
        return services.registry().get(id).orElse(null);
    }

    @Override
    protected Component title() {
        return Component.text("Shared with");
    }

    @Override
    public String breadcrumb() {
        return "Shared with";
    }

    @Override
    protected List<UUID> entries() {
        Mannequin mannequin = mannequin();
        return mannequin == null ? List.of() : new ArrayList<>(mannequin.trusted());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Shared with nobody yet",
                "<gray>Click the door below to trust somebody with this mannequin.");
    }

    @Override
    protected ItemStack icon(UUID who) {
        return Icons.head(who, "<white>" + nameOf(who),
                "<gray>May open and edit this mannequin.",
                "",
                "<dark_gray>click to stop trusting them");
    }

    @Override
    protected void onClick(UUID who, InventoryClickEvent event) {
        Mannequin mannequin = mannequin();
        if (mannequin == null) {
            return;
        }
        // Owner only, defensively — the button that opens this page is already greyed for anybody
        // else, but that is MannequinEditMenu's business, not this page's; a page that only trusts its
        // caller to have checked is one stale click away from a trusted member removing another.
        if (!mannequin.owner().equals(viewer.getUniqueId())) {
            services.messages().send(viewer, "mannequin.no-permission");
            return;
        }
        Mannequin updated = mannequin.withoutTrusted(who);
        services.mannequins().save(updated);
        services.messages().send(viewer, "mannequin.share.untrusted",
                "player", nameOf(who), "name", updated.displayName());
        refresh();
    }

    @Override
    protected void decorate() {
        super.decorate();
        Mannequin mannequin = mannequin();
        if (mannequin == null || !mannequin.owner().equals(viewer.getUniqueId())) {
            // Only the owner hands trust out — the same "the owner's to change" rule every claim
            // screen already prints for anything a trusted co-user may not do themselves.
            return;
        }
        toolbar(4, Icons.of(Material.OAK_DOOR, "<white>Trust somebody",
                        "<gray>Let them open and edit this mannequin too.",
                        "<dark_gray>click to choose who"),
                click -> askToTrust(mannequin));
    }

    private void askToTrust(Mannequin mannequin) {
        viewer.closeInventory();
        List<UUID> alreadyHere = new ArrayList<>(mannequin.trusted());
        alreadyHere.add(mannequin.owner());
        new PlayerChooser(viewer, services.brand(), this, "Trust somebody",
                alreadyHere, this::onTrustChosen).open();
    }

    private void onTrustChosen(PlayerEntry person) {
        Mannequin mannequin = mannequin();
        if (mannequin == null) {
            return;
        }
        Mannequin updated = mannequin.withTrusted(person.id());
        services.mannequins().save(updated);
        services.messages().send(viewer, "mannequin.share.trusted",
                "player", person.name(), "name", updated.displayName());
        Player theirs = services.server().getPlayer(person.id());
        if (theirs != null) {
            services.messages().send(theirs, "mannequin.share.notify-trusted",
                    "player", viewer.getName(), "name", updated.displayName());
        }
        open();
    }

    /** A trusted player's current name, falling back to their id for somebody the server has never seen. */
    private static String nameOf(UUID who) {
        String name = org.bukkit.Bukkit.getOfflinePlayer(who).getName();
        return name == null ? who.toString() : name;
    }

    @Override
    public String describe() {
        return "who besides the owner may open and edit this mannequin";
    }
}
