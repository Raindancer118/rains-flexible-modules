package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
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
            band(MenuLayout.WHO, column++, icon(slot, spec),
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

    /** The one non-per-slot action on this page: copy the viewer's own gear onto the mannequin. */
    @Override
    protected void decorate() {
        super.decorate();
        toolbar(4, Icons.of(Material.CHEST, "<green>Copy my current gear",
                        "<gray>Every piece of armor and whatever is in your",
                        "<gray>hands, material and enchants both —",
                        "<gray>empty slots are cleared to match."),
                click -> copyFromPlayer());
    }

    /**
     * The button itself, for a chosen slot, is the real item — enchant glint and all — not a
     * generic icon that only mentions the enchants in a line of lore. A player looking at a slot
     * that says "3 enchant(s)" cannot tell Sharpness V from three levels of Knockback without
     * opening the enchant screen; the actual enchanted stack answers that at a glance, exactly the
     * way hovering an item anywhere else in the game already does.
     *
     * <p>{@link ItemSpec#toItemStack()} builds the real stack; this only adds the "how to use this
     * button" lines underneath whatever lore that stack already carries from its own enchants.
     */
    private ItemStack icon(EquipmentSlot slot, ItemSpec spec) {
        if (spec == null || spec.material() == Material.AIR) {
            return Icons.of(placeholderFor(slot), "<white>" + slot.name(),
                    "<gray>Nothing chosen", "", "<gray>Click to choose a material");
        }
        ItemStack displayed = spec.toItemStack();
        ItemMeta meta = displayed.getItemMeta();
        meta.displayName(MINI.deserialize("<white>" + slot.name()));
        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        lore.add(Component.empty());
        lore.add(MINI.deserialize("<gray>Click to change it."));
        lore.add(MINI.deserialize("<gray>Right click to enchant it."));
        lore.add(MINI.deserialize("<gray>Shift + right click to remove it."));
        meta.lore(lore);
        displayed.setItemMeta(meta);
        return displayed;
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
        clearSlotWithoutRedrawing(slot);
        refresh();
    }

    /** The write half of {@link #clearSlot}, without the redraw — see {@link #copyFromPlayer}. */
    private void clearSlotWithoutRedrawing(EquipmentSlot slot) {
        mannequin = mannequin.withSlot(slot, null);
        services.mannequins().save(mannequin);
        services.mannequins().liveEntity(mannequin.id())
                .filter(org.bukkit.entity.Mannequin.class::isInstance)
                .map(org.bukkit.entity.Mannequin.class::cast)
                .ifPresent(live -> services.equip().apply(live, slot, ItemSpec.of(Material.AIR).toItemStack()));
    }

    /**
     * Copies every dressable slot from whatever the viewer is currently wearing and holding onto
     * the mannequin — same material, same enchants, in one click, rather than six trips through
     * {@link #pickMaterial} and {@link #enchant}.
     *
     * <h2>Why this is "the same stats" without copying durability</h2>
     * {@link ItemSpec} has never carried a live item's current wear, on purpose — it is what {@code
     * MannequinEquipService}'s durability-break rebuild treats as "the original choice" to return
     * to. Copying a player's own scuffed netherite chestplate would mean the mannequin's rebuild
     * target was already half broken; copying material and enchants gives the mannequin the same
     * gear a fresh copy of the player's items would be, which is what "looks the same and has the
     * same stats" means for a piece of equipment in the first place.
     */
    private void copyFromPlayer() {
        org.bukkit.inventory.PlayerInventory inventory = viewer.getInventory();
        copySlot(EquipmentSlot.HEAD, inventory.getHelmet());
        copySlot(EquipmentSlot.CHEST, inventory.getChestplate());
        copySlot(EquipmentSlot.LEGS, inventory.getLeggings());
        copySlot(EquipmentSlot.FEET, inventory.getBoots());
        copySlot(EquipmentSlot.HAND, inventory.getItemInMainHand());
        copySlot(EquipmentSlot.OFF_HAND, inventory.getItemInOffHand());
        refresh();
    }

    private void copySlot(EquipmentSlot slot, ItemStack real) {
        if (real == null || real.getType() == Material.AIR) {
            clearSlotWithoutRedrawing(slot);
            return;
        }
        apply(slot, new ItemSpec(real.getType(), real.getEnchantments()));
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

    /**
     * The second gesture: every enchantment the server actually has, paginated, each with a level
     * of its own.
     *
     * <h2>Why the whole registry, not a curated shortlist</h2>
     * The first version of this offered ten hand-picked enchants in a single, non-scrolling row —
     * every one beyond the seventh silently overlapped an earlier button in the same slot rather
     * than appearing at all, and even without that bug ten is far fewer than "every enchant a
     * player might reasonably want on a training dummy". Read here straight from {@link
     * RegistryAccess} — {@link RegistryKey#ENCHANTMENT} rather than the deprecated {@code
     * Registry.ENCHANTMENT} constant — so a future Minecraft version's new enchantments (a new
     * mace enchant, say) appear automatically rather than waiting for this list to be edited by
     * hand.
     */
    private final class EnchantsScreen extends PaginatedMenu<Enchantment> {

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
        protected List<Enchantment> entries() {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).stream()
                    .sorted(Comparator.comparing(enchant -> enchant.getKey().getKey()))
                    .toList();
        }

        /** Never actually empty — every server has at least the vanilla enchantments registered. */
        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.BARRIER, "<gray>This server has no enchantments registered");
        }

        @Override
        protected ItemStack icon(Enchantment enchant) {
            int level = spec.enchants().getOrDefault(enchant, 0);
            Material icon = level > 0 ? Material.ENCHANTED_BOOK : Material.BOOK;
            return Icons.of(icon, (level > 0 ? "<green>" : "<white>") + enchant.getKey().getKey(),
                    level > 0 ? "<green>Level " + level : "<gray>Not applied",
                    "",
                    "<gray>Click to set a level.",
                    level > 0 ? "<gray>Shift + click to remove it." : "");
        }

        @Override
        protected void onClick(Enchantment enchant, InventoryClickEvent event) {
            int level = spec.enchants().getOrDefault(enchant, 0);
            if (event.isShiftClick() && level > 0) {
                spec = spec.withEnchant(enchant, 0);
                apply(slot, spec);
                refresh();
                return;
            }
            new AmountChooser(viewer, brand(), this, enchant.getKey().getKey(), level, 0, 10,
                    chosenLevel -> {
                        spec = spec.withEnchant(enchant, chosenLevel);
                        apply(slot, spec);
                        refresh();
                    }).open();
        }

        /**
         * The description lives in the toolbar rather than {@code HEADER_SUBJECT} — that raw slot
         * sits inside the same top row {@link PaginatedMenu} already fills with entries, and
         * writing to it here would silently replace whichever enchant landed there on this page.
         *
         * <p>Column 2, not the more natural-looking centre: {@code paintChrome()} writes the page
         * counter (or the danger slot) at column 4 and the previous/next arrows at 3 and 5 <em>after</em>
         * this runs, and there are more registered enchantments than fit on one page — so anything
         * placed at 1, 3, 4, 5 or 6 here would be silently overwritten on every page but the last.
         */
        @Override
        protected void decorate() {
            super.decorate();
            toolbar(2, Icons.of(spec.material(), "<white>" + spec.material(),
                            "<gray>Click an enchant to set its level.",
                            "<dark_gray>Combinations vanilla refuses, and levels above the",
                            "<dark_gray>usual maximum, are both allowed here."),
                    click -> { });
        }
    }

    @Override
    public String describe() {
        return "choosing a mannequin's per-slot material and enchants";
    }
}
