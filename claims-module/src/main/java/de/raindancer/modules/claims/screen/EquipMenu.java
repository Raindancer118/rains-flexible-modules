package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.EquipRule;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What a claim keeps its people supplied with, and where it goes.
 *
 * <p>{@code ClaimEquipment.addRule} had no caller anywhere in the module before this: the auto-equip switch
 * on {@code PerksMenu} turned a system on that could never have a rule, and therefore never anything to
 * hand out. This is the rule editor the old plugin had, rebuilt on Core's menu — one entry per rule, each
 * an item and a destination, plus how many are left to give.
 *
 * <p>The stock itself lives in its own screen ({@code StoreMenu}, {@link de.raindancer.modules.claims.screen.StoreMenu.Kind#EQUIPMENT})
 * because rules and stock change at very different rates: the rules once, the stock whenever it runs low.
 */
public final class EquipMenu extends PaginatedMenu<Integer> implements IClaimScreen {

    private final ClaimServices services;
    private final Claim claim;

    public EquipMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Auto-equip rules");
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>Left-click a rule to change where it goes.",
                "<gray>Shift + right click removes it.");
    }

    private boolean mayManage() {
        return services.rights().canManage(claim, viewer(), ClaimAdminPermission.MANAGE_BANK);
    }

    @Override
    protected List<Integer> entries() {
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < claim.equipment().rules().size(); index++) {
            indices.add(index);
        }
        return indices;
    }

    @Override
    protected ItemStack icon(Integer index) {
        List<EquipRule> rules = claim.equipment().rules();
        if (index < 0 || index >= rules.size()) {
            return Icons.of(Material.BARRIER, "<red>gone");
        }
        EquipRule rule = rules.get(index);
        int inStock = claim.equipment().countMatching(rule.template());

        return Icons.of(rule.template().getType(),
                "<white>" + niceName(rule.template()),
                "<white>Goes to: <yellow>" + rule.describeTarget(),
                "<white>Keep topped up to: <yellow>" + rule.keepAmount(),
                "<white>In stock: " + (inStock > 0 ? "<green>" : "<red>") + inStock,
                "",
                "<yellow>Left-click <gray>change where it goes",
                "<yellow>Shift + right click <gray>remove this rule");
    }

    private static String niceName(ItemStack item) {
        return item.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    @Override
    protected void onClick(Integer index, InventoryClickEvent event) {
        if (!mayManage()) {
            services.messages().send(viewer(), "error.no-claim-permission");
            return;
        }
        List<EquipRule> rules = claim.equipment().rules();
        if (index < 0 || index >= rules.size()) {
            refresh();
            return;
        }
        if (event.isShiftClick() && event.isRightClick()) {
            claim.equipment().removeRule(index);
            claim.markDirty();
            services.claimService().saveAsync(claim);
            refresh();
            return;
        }
        new EquipTargetMenu(services, viewer(), this, claim, rules.get(index)).open();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.ARMOR_STAND, "<gray>No auto-equip rules",
                "<gray>Add one below: hold the item you want",
                "<gray>people to be kept supplied with.");
    }

    @Override
    protected void decorate() {
        super.decorate();

        int limit = services.config().maxEquipRules();
        boolean atLimit = claim.equipment().rules().size() >= limit;
        if (mayManage() && !atLimit) {
            toolbar(3, Icons.of(Material.TOTEM_OF_UNDYING, "<green><bold>Add the item in your hand",
                            "<white>Rules: <yellow>" + claim.equipment().rules().size() + "<gray>/" + limit,
                            "<gray>Hold what people should carry, then click."),
                    click -> {
                        ItemStack hand = viewer().getInventory().getItemInMainHand();
                        if (hand.getType().isAir()) {
                            services.messages().send(viewer(), "claim.hold-an-item");
                            return;
                        }
                        EquipRule rule = new EquipRule(hand, EquipRule.Target.AUTO, 0, 1);
                        claim.equipment().addRule(rule);
                        claim.markDirty();
                        services.claimService().saveAsync(claim);
                        // Straight into the destination picker: the slot is the whole point of the rule,
                        // and the automatic guess is only right for armour.
                        new EquipTargetMenu(services, viewer(), this, claim, rule).open();
                    });
        } else {
            toolbar(3, Icons.locked(
                    Icons.of(Material.GRAY_DYE, "<gray>Add the item in your hand",
                            "<white>Rules: <yellow>" + claim.equipment().rules().size() + "<gray>/" + limit),
                    atLimit ? "Remove one first" : "The owner's to change"),
                    click -> { });
        }

        toolbar(5, Icons.of(Material.CHEST, "<white>Equipment stock",
                        "<white>Items: <yellow>" + claim.equipment().totalStock(),
                        "<gray>What the rules hand out."),
                click -> new StoreMenu(services, viewer(), claim, this, StoreMenu.Kind.EQUIPMENT).open());
    }
}
