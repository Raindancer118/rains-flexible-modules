package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.EquipRule;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Where one auto-equip item goes: off hand, an armour slot, or a hotbar key the owner picks.
 *
 * <p>Every destination is on screen at once, including the nine hotbar keys, so choosing "off hand" is one
 * click rather than a guess at how far a toggle has cycled — which is how {@link EquipRule.Target} used to
 * have to be set before this screen existed.
 */
public final class EquipTargetMenu extends ClaimScreen {

    private static final EquipRule.Target[] NAMED_TARGETS = {
            EquipRule.Target.AUTO, EquipRule.Target.OFF_HAND, EquipRule.Target.HEAD,
            EquipRule.Target.CHEST, EquipRule.Target.LEGS, EquipRule.Target.FEET,
    };
    private static final Material[] NAMED_ICONS = {
            Material.COMPASS, Material.SHIELD, Material.IRON_HELMET,
            Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
    };

    private final EquipRule rule;

    public EquipTargetMenu(ClaimServices services, Player viewer, Menu parent, Claim claim, EquipRule rule) {
        super(services, viewer, claim, parent);
        this.rule = rule;
    }

    @Override
    protected Component title() {
        return Component.text("Where should it go?");
    }

    @Override
    protected void render() {
        boolean allowed = may(ClaimAdminPermission.MANAGE_BANK);

        set(MenuLayout.HEADER_SUBJECT, Icons.of(rule.template().getType(),
                "<aqua><bold>Destination",
                "<white>Currently: <yellow>" + rule.describeTarget(),
                "<white>Keep topped up to: <yellow>" + rule.keepAmount()));

        for (int index = 0; index < NAMED_TARGETS.length; index++) {
            EquipRule.Target target = NAMED_TARGETS[index];
            boolean active = rule.target() == target;
            band(MenuLayout.WHO, index + 1, allowed,
                    Icons.of(NAMED_ICONS[index], (active ? "<green>" : "<white>") + target.displayName(),
                            "<gray>" + target.description(),
                            "",
                            active ? "<green>* in use" : "<yellow>Click to use"),
                    "The owner's to change",
                    click -> {
                        rule.target(target);
                        save();
                    });
        }

        // The nine hotbar keys as a full-width grid row, mirroring the player's own hotbar.
        for (int slot = 0; slot < 9; slot++) {
            boolean active = rule.target() == EquipRule.Target.HOTBAR && rule.hotbarSlot() == slot;
            int chosen = slot;
            cell(2, slot, Icons.of(active ? Material.LIME_STAINED_GLASS_PANE
                            : Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            (active ? "<green>" : "<white>") + "Hotbar key " + (slot + 1),
                            "<gray>Put it on this hotbar key.",
                            "",
                            active ? "<green>* in use" : "<yellow>Click to use"),
                    click -> {
                        if (!allowed) {
                            tell("error.no-claim-permission");
                            return;
                        }
                        rule.target(EquipRule.Target.HOTBAR);
                        rule.hotbarSlot(chosen);
                        save();
                    });
        }

        band(MenuLayout.LAND, 4, allowed,
                Icons.of(Material.PAPER, "<aqua>Keep topped up to: <white>" + rule.keepAmount(),
                        "<gray>How many the player should be carrying.",
                        "<gray>One for a totem, a stack for fireworks.",
                        "",
                        "<dark_gray>Click to choose a number."),
                "The owner's to change",
                // Core's picker rather than ±1 nudges with shift for ten: a stack of sixty-four was
                // seven shift-clicks and a lot of counting, and overshooting meant going back.
                click -> new de.raindancer.core.ui.choose.AmountChooser(viewer, services().brand(),
                        this, "How many to keep", rule.keepAmount(), 1,
                        rule.template().getMaxStackSize(),
                        chosen -> {
                            rule.keepAmount(chosen);
                            save();
                        }).open());
    }

    private void save() {
        claim().markDirty();
        services().claimService().saveAsync(claim());
        services().messages().send(viewer, "equip.target-set",
                "item", rule.template().getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                "where", rule.describeTarget());
        refresh();
    }
}
