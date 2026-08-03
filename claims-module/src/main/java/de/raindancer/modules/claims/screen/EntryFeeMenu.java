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
                Icons.of(fee.type().icon(), "<white>How much: <green>" + fee.amount(),
                        "<gray>" + fee.type().displayName() + ", per crossing.",
                        "",
                        "<dark_gray>click to set it — up to "
                                + services().config().entryFeeMaxAmount()),
                "The owner's to change",
                // Core's picker rather than ±1 nudges: forty clicks to reach four hundred is why the old
                // screen had a chat prompt beside it, and nothing is applied until Accept.
                click -> new de.raindancer.core.ui.choose.AmountChooser(viewer, services().brand(), this,
                        "How much to charge", fee.amount(), 1,
                        services().config().entryFeeMaxAmount(),
                        chosen -> {
                            fee.amount(chosen);
                            save();
                        }).open());

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
            boolean chosen = fee.item() != null;
            toolbar(4, Icons.of(chosen ? fee.item().getType() : Material.BARRIER,
                            chosen
                                    ? "<white>They hand over <green>" + friendly(fee.item().getType())
                                    : "<red>No item chosen yet",
                            chosen
                                    ? "<gray>" + fee.amount() + " of them, per crossing."
                                    : "<gray>Until one is picked, nobody can pay.",
                            "",
                            "<dark_gray>click to choose from every item"),
                    // Core's item chooser rather than "hold one and click": holding it meant owning it, so an
                    // owner could not charge for anything they did not already have in their hand.
                    click -> new de.raindancer.core.ui.choose.ItemChooser(viewer, services().brand(), this,
                            "What they pay with",
                            material -> {
                                fee.item(new org.bukkit.inventory.ItemStack(material));
                                save();
                            }).open());
        }
    }

    /** A material as somebody would say it: "gold ingot", not "GOLD_INGOT". */
    private static String friendly(Material material) {
        return material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private void save() {
        claim().markDirty();
        services().claimService().saveAsync(claim());
        refresh();
    }
}
