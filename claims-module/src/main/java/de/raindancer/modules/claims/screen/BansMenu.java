package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Who is kept out, and until when.
 *
 * <p>A timeout and a ban are the same thing with and without an end, which is why they are one list rather than
 * two screens — and why the remaining time is on the button instead of in a separate "timeouts" page nobody would
 * think to open.
 */
public final class BansMenu extends PaginatedMenu<ClaimBan> {

    private final ClaimServices services;
    private final Claim claim;

    public BansMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Kept out");
    }

    @Override
    protected List<ClaimBan> entries() {
        return new ArrayList<>(claim.bans().values());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.LIME_STAINED_GLASS_PANE, "<green>Nobody is barred",
                "<gray>Bar somebody with <white>/claim ban <name></white>.");
    }

    @Override
    protected ItemStack icon(ClaimBan ban) {
        List<String> lore = new ArrayList<>();
        lore.add(ban.permanent()
                ? "<red>Barred for good"
                : "<gold>" + Durations.describe(Duration.ofMillis(ban.remainingMillis())) + " left");
        lore.add("<gray>" + (ban.reason().isBlank() ? "no reason given" : ban.reason()));
        lore.add("");
        lore.add("<dark_gray>click to let them back in");
        return Icons.head(ban.uuid(), "<red>" + services.names().nameOfOwner(ban.uuid()), lore);
    }

    @Override
    protected void onClick(ClaimBan ban, InventoryClickEvent event) {
        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_BANS)) {
            services.messages().send(viewer, "error.no-claim-permission");
            return;
        }
        claim.unban(ban.uuid());
        services.claimService().saveAsync(claim);
        services.broadcasts().lifted(claim, services.names().nameOfOwner(ban.uuid()),
                viewer.getName());
        services.messages().send(viewer, "claim.ban-lifted",
                "player", services.names().nameOfOwner(ban.uuid()), "claim", claim.name());
        refresh();
    }
}
