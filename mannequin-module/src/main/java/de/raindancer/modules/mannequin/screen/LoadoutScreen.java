package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Map;

/**
 * Per equipment slot: a material, and then enchants for it — including combinations vanilla would
 * refuse and levels above the usual maximum, since a training dummy's loadout is never obtainable
 * in survival and so has no obtainability rule to obey.
 *
 * <h2>Left click changes it, right click enchants it, shift + right click clears it</h2>
 * Three gestures on the one slot button, because the alternative — a submenu just to reach an
 * enchant screen for an item already chosen — is a click spent getting somewhere the button could
 * have gone directly. Nothing here is silent: {@code ScreenGrammarTest} fails the build if either
 * modifier stops being named in the button's own lore.
 *
 * <h2>Why this class updates its own field rather than reading the constructor's argument forever</h2>
 * Every one of the three gestures writes through {@link #apply}, and {@link #apply} both saves to
 * the store <em>and</em> reassigns {@link #mannequin} to the result. Building the next write from
 * the constructor's original snapshot instead — the earlier shape of this class — meant a second
 * slot edited in the same visit was computed against a loadout that did not yet know about the
 * first: two changes in one sitting silently kept only the second, because each write recomputed
 * "the whole mannequin plus this one change" from the same stale starting point. Kept current
 * after every write, the second edit is built on top of the first instead of overwriting it.
 *
 * <p>Every change is written through {@code MannequinEquipService#apply}, never any other way, and
 * the spec kept in a slot — not the live stack — is what {@code MannequinEquipServiceTest} and the
 * durability rebuild both rely on staying the single source of truth for what a slot is
 * <em>supposed</em> to hold.
 */
public final class LoadoutScreen extends Menu implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Common, useful enchants to offer — not exhaustive, but enough that a curator does not stub. */
    private static final List<Enchantment> CURATED_ENCHANTS = List.of(
            Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.PROTECTION,
            Enchantment.PROJECTILE_PROTECTION, Enchantment.BLAST_PROTECTION,
            Enchantment.UNBREAKING, Enchantment.KNOCKBACK, Enchantment.FIRE_ASPECT,
            Enchantment.THORNS, Enchantment.MENDING);

    private final MannequinServices services;
    private Mannequin mannequin;

    public LoadoutScreen(MannequinServices services, Player viewer, Mannequin mannequin, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.mannequin = mannequin;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Loadout — " + mannequin.displayName());
    }

    @Override
    public String breadcrumb() {
        return "Loadout";
    }

    @Override
    protected void render() {
        int column = 1;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!isDressable(slot)) {
                continue;
            }
            ItemSpec spec = mannequin.specFor(slot);
            Material icon = spec == null ? placeholderFor(slot) : spec.material();
            band(MenuLayout.WHO, column++, Icons.of(icon, "<white>" + slot.name(), lore(spec)),
                    click -> {
                        if (click.isRightClick() && click.isShiftClick()) {
                            clearSlot(slot);
                            return;
                        }
                        if (click.isRightClick()) {
                            enchant(slot);
                            return;
                        }
                        pickMaterial(slot);
                    });
        }
    }

    private List<String> lore(ItemSpec spec) {
        if (spec == null) {
            return List.of("<gray>Nothing chosen", "", "<gray>Click to choose a material");
        }
        return List.of(
                "<gray>" + spec.material(),
                spec.enchants().isEmpty() ? "<dark_gray>No enchants"
                        : "<dark_gray>" + spec.enchants().size() + " enchant(s)",
                "",
                "<gray>Click to change it.",
                "<gray>Right click to enchant it.",
                "<gray>Shift + right click to remove it.");
    }

    private boolean isDressable(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET
                || slot == EquipmentSlot.HAND || slot == EquipmentSlot.OFF_HAND;
    }

    private Material placeholderFor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Material.LEATHER_HELMET;
            case CHEST -> Material.LEATHER_CHESTPLATE;
            case LEGS -> Material.LEATHER_LEGGINGS;
            case FEET -> Material.LEATHER_BOOTS;
            case OFF_HAND -> Material.SHIELD;
            default -> Material.WOODEN_SWORD;
        };
    }

    /** Left click: pick a material for this slot, kept unenchanted until a right click adds one. */
    private void pickMaterial(EquipmentSlot slot) {
        new ItemChooser(viewer, brand(), this, "Material for " + slot.name(),
                chosen -> apply(slot, new ItemSpec(chosen, Map.of())),
                new Catalogue(() -> materialsFor(slot))).open();
    }

    /** A curated list rather than the whole registry — armor for armor slots, weapons for hands. */
    private List<String> materialsFor(EquipmentSlot slot) {
        List<Material> materials = switch (slot) {
            case HEAD -> List.of(Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET,
                    Material.IRON_HELMET, Material.GOLDEN_HELMET, Material.DIAMOND_HELMET,
                    Material.NETHERITE_HELMET, Material.TURTLE_HELMET);
            case CHEST -> List.of(Material.LEATHER_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE,
                    Material.IRON_CHESTPLATE, Material.GOLDEN_CHESTPLATE, Material.DIAMOND_CHESTPLATE,
                    Material.NETHERITE_CHESTPLATE, Material.ELYTRA);
            case LEGS -> List.of(Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS,
                    Material.IRON_LEGGINGS, Material.GOLDEN_LEGGINGS, Material.DIAMOND_LEGGINGS,
                    Material.NETHERITE_LEGGINGS);
            case FEET -> List.of(Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS,
                    Material.IRON_BOOTS, Material.GOLDEN_BOOTS, Material.DIAMOND_BOOTS,
                    Material.NETHERITE_BOOTS);
            case OFF_HAND -> List.of(Material.SHIELD, Material.TOTEM_OF_UNDYING, Material.AIR);
            default -> List.of(Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
                    Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD,
                    Material.TRIDENT, Material.BOW, Material.CROSSBOW, Material.NETHERITE_AXE);
        };
        return materials.stream().map(Enum::name).toList();
    }

    /** Right click: enchant whatever is already chosen. Nothing to enchant yet falls back to picking one. */
    private void enchant(EquipmentSlot slot) {
        ItemSpec current = mannequin.specFor(slot);
        if (current == null) {
            pickMaterial(slot);
            return;
        }
        new EnchantsScreen(viewer, brand(), this, slot, current).open();
    }

    /**
     * Shift + right click: clear the slot back to nothing, on the mannequin and on the live
     * entity. The empty stack comes from {@link ItemSpec} rather than being built here directly —
     * a screen constructing its own {@code ItemStack} is exactly what {@code ScreenGrammarTest}'s
     * {@code nobodyBuildsTheirOwnItemStacks} exists to catch, even for a real equipment slot rather
     * than a button icon.
     */
    private void clearSlot(EquipmentSlot slot) {
        mannequin = mannequin.withSlot(slot, null);
        services.mannequins().save(mannequin);
        services.mannequins().liveEntity(mannequin.id())
                .filter(org.bukkit.entity.Mannequin.class::isInstance)
                .map(org.bukkit.entity.Mannequin.class::cast)
                .ifPresent(live -> services.equip().apply(live, slot, ItemSpec.of(Material.AIR).toItemStack()));
        refresh();
    }

    /**
     * Writes one slot's spec, on both the stored mannequin and — if it is live right now — the
     * real entity's equipment. Always builds on {@link #mannequin} as it stands <em>this instant</em>
     * and immediately reassigns it to the result, which is what keeps a run of several edits in one
     * visit from each overwriting the last (see the class doc).
     */
    private void apply(EquipmentSlot slot, ItemSpec spec) {
        mannequin = mannequin.withSlot(slot, spec);
        services.mannequins().save(mannequin);
        services.mannequins().liveEntity(mannequin.id())
                .filter(org.bukkit.entity.Mannequin.class::isInstance)
                .map(org.bukkit.entity.Mannequin.class::cast)
                .ifPresent(live -> services.equip().apply(live, slot, spec.toItemStack()));
    }

    /** The second gesture: pick a level for each of a curated set of enchants, or none at all. */
    private final class EnchantsScreen extends Menu {

        private final EquipmentSlot slot;
        private ItemSpec spec;

        private EnchantsScreen(Player viewer, de.raindancer.core.ui.chat.Brand brand, Menu parent,
                               EquipmentSlot slot, ItemSpec spec) {
            super(viewer, brand, parent);
            this.slot = slot;
            this.spec = spec;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<dark_gray>Enchants — " + spec.material());
        }

        @Override
        public String breadcrumb() {
            return "Enchants";
        }

        @Override
        protected void render() {
            set(MenuLayout.HEADER_SUBJECT, Icons.of(spec.material(), "<white>" + spec.material(),
                    "<gray>Click an enchant to set its level.",
                    "<dark_gray>Combinations vanilla refuses, and levels above the",
                    "<dark_gray>usual maximum, are both allowed here."));

            int column = 1;
            for (Enchantment enchant : CURATED_ENCHANTS) {
                int level = spec.enchants().getOrDefault(enchant, 0);
                Material icon = level > 0 ? Material.ENCHANTED_BOOK : Material.BOOK;
                band(MenuLayout.WHO, ((column - 1) % 7) + 1, Icons.of(icon,
                                "<white>" + enchant.getKey().getKey(),
                                level > 0 ? "<green>Level " + level : "<gray>Not applied",
                                "",
                                "<dark_gray>Click to set a level"),
                        click -> new AmountChooser(viewer, brand(), this,
                                enchant.getKey().getKey(), level, 0, 10, chosenLevel -> {
                            spec = spec.withEnchant(enchant, chosenLevel);
                            apply(slot, spec);
                            refresh();
                        }).open());
                column++;
            }
        }
    }

    @Override
    public String describe() {
        return "choosing a mannequin's per-slot material and enchants";
    }
}
