package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/** What the claim charges at the border, and what it has collected. */
public final class MoneyMenu extends ClaimScreen {

    public MoneyMenu(ClaimServices services, org.bukkit.entity.Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text("Toll and bank — " + claim().name());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>The two halves of the same thing:",
                "",
                "<white>Entry fee</white> <dark_gray>·</dark_gray> <gray>what a visitor pays",
                "<white>Bank</white> <dark_gray>·</dark_gray> <gray>where it ends up");
    }

    @Override
    protected void render() {
        Claim claim = claim();

        if (services().features().isOffered(ClaimFeature.ENTRY_FEE)) {
            band(MenuLayout.RULES, 3, may(ClaimAdminPermission.MANAGE_ENTRY_FEE),
                    Icons.of(Material.GOLD_NUGGET, "<yellow>Entry fee",
                            "<gray>What a visitor pays at the border.",
                            "<dark_gray>" + (claim.entryFee().enabled() ? "charging" : "free")),
                    "The owner's to change",
                    click -> new EntryFeeMenu(services(), viewer, claim, this).open());
        }

        if (services().features().isOffered(ClaimFeature.BANK)) {
            band(MenuLayout.RULES, 5, may(ClaimAdminPermission.MANAGE_BANK),
                    Icons.of(Material.ENDER_CHEST, "<yellow>Bank",
                            "<gray>Items and experience the claim holds.",
                            "<dark_gray>" + claim.bank().items().size() + " item(s)"),
                    "The owner's to change",
                    click -> new BankMenu(services(), viewer, claim, this).open());
        }
    }
}
