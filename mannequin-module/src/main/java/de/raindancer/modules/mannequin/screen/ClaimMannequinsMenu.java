package de.raindancer.modules.mannequin.screen;

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

import java.util.List;
import java.util.UUID;

/**
 * Every mannequin belonging to one claim — the submenu {@code claims-module}'s own {@code ClaimMenu}
 * shows when this module is installed alongside it.
 *
 * <h2>Held by the claim's id, not by a {@code Claim}</h2>
 * The same reason every other list on this side already reads the registry fresh rather than trusting
 * what it was opened with: a claim's mannequins can change while this page is open, and this page is
 * itself only ever reachable through {@code de.raindancer.modules.mannequin.claims}, which already had
 * to resolve a live claim to get here — holding only the id keeps this class from needing a {@code
 * Claim} reference of its own.
 */
public final class ClaimMannequinsMenu extends PaginatedMenu<Mannequin> implements IMannequinScreen {

    private final MannequinServices services;
    private final UUID claimId;
    private final String claimName;

    public ClaimMannequinsMenu(MannequinServices services, Player viewer, UUID claimId, String claimName,
                               Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claimId = claimId;
        this.claimName = claimName;
    }

    @Override
    protected Component title() {
        return Component.text("Mannequins");
    }

    @Override
    public String breadcrumb() {
        return "Mannequins";
    }

    @Override
    protected List<Mannequin> entries() {
        return services.registry().inClaim(claimId);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.ARMOR_STAND, "<gray>No mannequins belong to " + claimName + " yet",
                "<gray>Stand inside it and create one with <white>/mannequin create</white>,",
                "<gray>then link it here from its own edit page.");
    }

    @Override
    protected ItemStack icon(Mannequin mannequin) {
        List<String> lore = List.of(
                "<dark_gray>" + mannequin.world() + " " + mannequin.x() + " "
                        + mannequin.y() + " " + mannequin.z(),
                "",
                "<gray>Click to open.");
        if (mannequin.skinSource() != null) {
            return Icons.head(mannequin.skinSource(), "<white>" + mannequin.displayName(), lore);
        }
        return Icons.of(Material.ARMOR_STAND, "<white>" + mannequin.displayName(), lore);
    }

    @Override
    protected void onClick(Mannequin mannequin, InventoryClickEvent event) {
        services.registry().get(mannequin.id())
                .ifPresentOrElse(
                        current -> new MannequinEditMenu(services, viewer, current, this).open(),
                        () -> {
                            services.messages().send(viewer, "mannequin.unknown-id", "id", mannequin.id());
                            refresh();
                        });
    }

    @Override
    public String describe() {
        return "every mannequin belonging to one claim";
    }
}
