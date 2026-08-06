package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.loot.LootEntry;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One loot entry: what it is, how much of it, and how likely it is to be picked.
 *
 * <p>Everything this page can set is everything Core's {@link LootEntry} can hold — see
 * {@link LootTableMenu}'s class note on the fields the source plugin's entry had that this one cannot,
 * because the record it is built from does not carry them.
 *
 * <p>The entry is addressed by its index into the table's current list rather than by identity, because
 * {@link LootEntry} is a record with no id of its own — two entries for the same material and weight are
 * equal, and a table is free to contain both. The index is read fresh from the catalogue on every render
 * ({@link #current()}), so a page left open across somebody else's edit still points at the row it was
 * opened on rather than at whatever now happens to sit at that position... except that the source of
 * truth here is the module's own admin suite, opened by one person at a time in practice; this note exists
 * so that a stale index is a known, accepted risk rather than a silent one.
 */
public final class LootEntryMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final LootCatalogue catalogue;
    private final CustomItems customItems;
    private final String tableName;
    private final int index;

    public LootEntryMenu(Player viewer, Brand brand, Menu parent, LootCatalogue catalogue, String tableName,
                         int index) {
        this(viewer, brand, parent, catalogue, null, tableName, index);
    }

    /** @param customItems required only for the "custom item" button; may be {@code null} to hide it */
    public LootEntryMenu(Player viewer, Brand brand, Menu parent, LootCatalogue catalogue, CustomItems customItems,
                         String tableName, int index) {
        super(viewer, brand, parent);
        this.catalogue = catalogue;
        this.customItems = customItems;
        this.tableName = tableName;
        this.index = index;
    }

    private LootTable table() {
        return catalogue.byName(tableName).orElse(null);
    }

    private LootEntry current() {
        LootTable table = table();
        return table != null && index >= 0 && index < table.entries().size() ? table.entries().get(index) : null;
    }

    @Override
    protected Component title() {
        LootEntry entry = current();
        return MINI.deserialize("<dark_gray>Entry — <white>"
                + (entry == null ? "?" : entry.isCustom() ? entry.customKey() : entry.material().name()));
    }

    @Override
    public String breadcrumb() {
        return "Entry";
    }

    @Override
    protected void render() {
        LootEntry entry = current();
        if (entry == null) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>This entry is gone",
                    "<gray>It was removed from the table already."));
            return;
        }
        LootTable table = table();
        int total = table.totalWeight();
        double chance = total > 0 ? entry.weight() * 100.0 / total : 0;

        band(MenuLayout.WHO, 4, Icons.of(entry.isCustom() ? Material.SPYGLASS : entry.material(),
                "<yellow>" + (entry.isCustom() ? entry.customKey() : entry.material().name()),
                String.format(java.util.Locale.ROOT, "<gray>%.1f%% of this table's rolls", chance)));

        band(MenuLayout.RULES, 2, Icons.of(Material.CHEST, "<yellow>Item: "
                        + (entry.isCustom() ? entry.customKey() : entry.material().name()),
                        "<gray>Pick a vanilla material.",
                        "<dark_gray>Click to choose."),
                click -> new ItemChooser(viewer, brand(), this, "Pick a material",
                        material -> replace(LootEntry.of(material, entry.minimum())
                                .amount(entry.minimum(), entry.maximum()).weight(entry.weight()))).open());

        if (customItems != null) {
            band(MenuLayout.RULES, 3, Icons.of(Material.NETHER_STAR, "<light_purple>Custom item",
                            "<gray>Reward one of this server's own items instead.",
                            "<dark_gray>Click to choose."),
                    click -> new CustomItemPicker().open());
        }

        band(MenuLayout.RULES, 4, Icons.of(Material.REPEATER, "<yellow>Min amount: " + entry.minimum(),
                        "<gray>The fewest ever rolled.", "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Min amount", entry.minimum(), 1, 64,
                        value -> replace(entry.amount(value, Math.max(value, entry.maximum())))).open());

        band(MenuLayout.RULES, 5, Icons.of(Material.REPEATER, "<yellow>Max amount: " + entry.maximum(),
                        "<gray>The most ever rolled.", "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Max amount", entry.maximum(),
                        entry.minimum(), 64, value -> replace(entry.amount(entry.minimum(), value))).open());

        band(MenuLayout.RULES, 6, Icons.of(Material.COMPARATOR, "<yellow>Weight: " + entry.weight(),
                        "<gray>Its relative chance in this table's pool.", "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Weight", entry.weight(), 1, 1_000,
                        value -> replace(entry.weight(value))).open());
    }

    private void replace(LootEntry newEntry) {
        LootTable table = table();
        if (table == null) {
            return;
        }
        List<LootEntry> updated = new ArrayList<>(table.entries());
        if (index >= 0 && index < updated.size()) {
            updated.set(index, newEntry);
        } else {
            updated.add(newEntry);
        }
        catalogue.define(tableName, table.tier(), table.fillPercent(), updated);
        refresh();
    }

    /**
     * The small list Core has no ready-made chooser for: this server's custom items, by id.
     *
     * <p>Not a new picker framework — one page, built the same way {@link LootTablesMenu} and every other
     * list in this module is, over Core's own {@link PaginatedMenu}. What {@code ScreenGrammarTest} forbids
     * is reinventing a chooser Core already has one of; Core has none for "this server's custom items", so
     * this is the module's own content, not a second {@code ItemChooser}.
     */
    private final class CustomItemPicker extends PaginatedMenu<CustomItem> implements IHungerGamesScreen {

        CustomItemPicker() {
            super(LootEntryMenu.this.viewer, LootEntryMenu.this.brand(), LootEntryMenu.this);
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<dark_gray>Custom items");
        }

        @Override
        public String breadcrumb() {
            return "Custom items";
        }

        @Override
        protected List<CustomItem> entries() {
            return customItems.all();
        }

        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.BARRIER, "<gray>No custom items", "<gray>None are defined on this server.");
        }

        @Override
        protected ItemStack icon(CustomItem item) {
            return Icons.of(item.material(), "<light_purple>" + item.nameOrId(), "<dark_gray>" + item.key());
        }

        @Override
        protected void onClick(CustomItem item, InventoryClickEvent event) {
            LootEntry entry = current();
            int min = entry == null ? 1 : entry.minimum();
            int max = entry == null ? 1 : entry.maximum();
            int weight = entry == null ? 10 : entry.weight();
            replace(LootEntry.ofCustomItem(item.id(), min).amount(min, max).weight(weight));
        }

        @Override
        public String describe() {
            return "this server's custom items, for a loot entry that rewards one";
        }
    }

    @Override
    public String describe() {
        return "one loot entry: what it is, how much, and how likely";
    }
}
