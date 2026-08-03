package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.protection.FlagPolicy;
import de.raindancer.core.world.protection.FlagRules;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What the world does inside this border.
 *
 * <p>Paginated, because there are twenty-two of them and a page of twenty-two toggles is a wall. Only the ones the
 * server actually enforces are listed: a flag an admin has switched off is not a choice the owner has, and showing
 * it as one produces a click that does nothing.
 *
 * <h2>Left click sets it, right click opens the tiers</h2>
 * Most owners want "fire off" and nothing more, so a plain click cycles the whole claim. The ones who want fall
 * damage off for themselves and on for visitors get that behind a right click, which keeps the common case one
 * click and the uncommon case possible — rather than the old screen's arrangement, where every flag cost two
 * clicks because every flag might have been the uncommon one.
 */
public final class FlagsMenu extends PaginatedMenu<LandFlag> {

    private final ClaimServices services;
    private final Claim claim;

    public FlagsMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Rules");
    }

    @Override
    protected List<LandFlag> entries() {
        return services.flags().editableFlags();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nothing to set",
                "<gray>This server does not leave any of the rules to owners.");
    }

    @Override
    protected ItemStack icon(LandFlag flag) {
        FlagRules rules = services.flags();
        FlagRules.Summary summary = rules.summarise(claim.area(), flag);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + services.messages().raw(flag.descriptionKey()));
        lore.add("");
        lore.add(switch (summary) {
            case ALLOWED -> "<green>✔ allowed";
            case DENIED -> "<red>✘ not allowed";
            case MIXED -> "<yellow>◐ different per group";
        });
        if (claim.flagOverride(flag, LandAudience.OWNER).isEmpty()) {
            lore.add("<dark_gray>following the server default");
        }
        lore.add("");
        lore.add("<dark_gray>click to change");
        if (flag.audienceAware()) {
            lore.add("<dark_gray>right click for owners / trusted / visitors");
        }
        return Icons.of(flag.icon(), summary.colour() + services.messages().raw(flag.nameKey()), lore);
    }

    @Override
    protected void onClick(LandFlag flag, InventoryClickEvent event) {
        if (!services.rights().canManage(claim, viewer, ClaimAdminPermission.MANAGE_FLAGS)) {
            services.messages().send(viewer, "error.no-claim-permission");
            return;
        }
        if (services.flags().policy(flag) != FlagPolicy.AVAILABLE) {
            // Should not be reachable — entries() filters these out — but a stale open window could.
            services.messages().send(viewer, "claim.flag-not-yours", "flag",
                    services.messages().raw(flag.nameKey()));
            return;
        }
        if (event.isRightClick() && flag.audienceAware()) {
            new FlagAudienceMenu(services, viewer, claim, this, flag).open();
            return;
        }
        boolean now = services.flags().isAllowed(claim.area(), flag, LandAudience.OWNER);
        claim.setFlagOverride(flag, null, !now);
        services.claimService().saveAsync(claim);
        refresh();
    }
}
