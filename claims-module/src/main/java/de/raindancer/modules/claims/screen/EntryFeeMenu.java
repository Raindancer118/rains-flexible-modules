package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.CostType;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What a visitor pays at the border.
 *
 * <p>Four decisions: whether to charge, in what, how much, and how long a paid pass lasts. The pass length is the
 * one that matters most and was hardest to find before — without it a visitor is asked again every time they step
 * over the line, which is the behaviour that made people turn entry fees off.
 */
public final class EntryFeeMenu extends ClaimScreen {

    public EntryFeeMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("Entry fee");
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>Somebody who pays gets a pass for a while,",
                "<gray>so crossing the border twice does not cost twice.",
                "",
                "<gray>Trusted players and owners never pay.");
    }

    @Override
    protected void render() {
        Claim claim = claim();
        boolean allowed = may(ClaimAdminPermission.MANAGE_ENTRY_FEE);
        var fee = claim.entryFee();

        band(MenuLayout.WHO, 2, allowed,
                Icons.of(fee.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                        fee.enabled() ? "<green>Charging" : "<gray>Free to enter",
                        "<dark_gray>click to change"),
                "The owner's to change",
                click -> {
                    fee.enabled(!fee.enabled());
                    save();
                });

        band(MenuLayout.WHO, 3, allowed,
                Icons.of(fee.type().icon(), "<white>Paid in <green>" + fee.type().displayName(),
                        "<gray>" + fee.type().description(),
                        "<dark_gray>click for the next kind"),
                "The owner's to change",
                click -> {
                    CostType[] kinds = CostType.values();
                    fee.type(kinds[(fee.type().ordinal() + 1) % kinds.length]);
                    save();
                });

        band(MenuLayout.WHO, 5, allowed,
                Icons.of(Material.GOLD_INGOT, "<white>How much: <green>" + fee.amount(),
                        "<gray>Left click +1, right click −1.",
                        "<gray>Shift for ten at a time.",
                        "<dark_gray>the server allows up to " + services().config().entryFeeMaxAmount()),
                "The owner's to change",
                click -> {
                    int step = click.isShiftClick() ? 10 : 1;
                    int wanted = fee.amount() + (click.isRightClick() ? -step : step);
                    fee.amount(Math.max(1, Math.min(services().config().entryFeeMaxAmount(), wanted)));
                    save();
                });

        band(MenuLayout.WHO, 6, allowed,
                Icons.of(Material.CLOCK, "<white>Pass lasts <green>" + fee.passDurationSeconds() + "s",
                        "<gray>How long before they are asked again.",
                        "<gray>Left click +30s, right click −30s.",
                        "<dark_gray>0 means every crossing pays"),
                "The owner's to change",
                click -> {
                    int step = click.isShiftClick() ? 300 : 30;
                    int wanted = fee.passDurationSeconds() + (click.isRightClick() ? -step : step);
                    fee.passDurationSeconds(Math.max(0, Math.min(86_400, wanted)));
                    save();
                });

        if (fee.type() == CostType.ITEM) {
            toolbar(4, Icons.of(fee.item() == null ? Material.BARRIER : fee.item().getType(),
                            "<white>The item they hand over",
                            "<gray>Hold one and click to set it."),
                    click -> {
                        var held = viewer.getInventory().getItemInMainHand();
                        if (held.getType().isAir()) {
                            tell("claim.hold-an-item");
                            return;
                        }
                        fee.item(held.clone());
                        save();
                    });
        }
    }

    private void save() {
        claim().markDirty();
        services().claimService().saveAsync(claim());
        refresh();
    }
}
