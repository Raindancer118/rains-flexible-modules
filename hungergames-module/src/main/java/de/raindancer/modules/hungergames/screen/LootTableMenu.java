package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.loot.LootEntry;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One loot table's entries, each shown with the chance it actually rolls at.
 *
 * <h2>What this cannot offer, because Core's {@link LootEntry} does not carry it</h2>
 * The source plugin's entry could hold a display name, custom lore, a list of enchantments and an
 * unbreakable flag — none of which exist on Core's {@link LootEntry}: a material or a custom item's id, a
 * weight, and a minimum/maximum amount, and nothing else. That is Core's own loot model, shared with every
 * other module that rolls a table, and this page edits through it rather than pretending it has fields it
 * does not — see {@link LootEntryMenu}.
 *
 * <h2>Shift-left removes, right toggles nothing</h2>
 * There is no per-entry enable/disable here either, for the same reason: {@link LootEntry} has no such flag.
 * An entry an owner does not want is removed outright, which {@link LootCatalogue#define} makes safe to undo
 * by re-adding it — nothing here is written until the redefine, and the redefine is one call.
 */
public final class LootTableMenu extends PaginatedMenu<LootEntry> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final LootCatalogue catalogue;
    private final GameSession session;
    private final HungerGamesSettings settings;
    private final CustomItems customItems;
    private final String tableName;

    public LootTableMenu(Player viewer, Brand brand, Menu parent, LootCatalogue catalogue, GameSession session,
                         HungerGamesSettings settings, CustomItems customItems, String tableName) {
        super(viewer, brand, parent);
        this.catalogue = catalogue;
        this.session = session;
        this.settings = settings;
        this.customItems = customItems;
        this.tableName = tableName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Table — <white>" + tableName);
    }

    @Override
    public String breadcrumb() {
        return tableName;
    }

    private boolean edit() {
        return LootMenu.canEdit(PermissionNodes.isAdmin(viewer), settings, session.phase());
    }

    private LootTable table() {
        return catalogue.byName(tableName).orElse(null);
    }

    @Override
    protected List<LootEntry> entries() {
        LootTable table = table();
        return table == null ? List.of() : table.entries();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No entries",
                edit() ? "<gray>Use \"Add entry\" below." : "<gray>Nothing rolls from this table.");
    }

    @Override
    protected ItemStack icon(LootEntry entry) {
        LootTable table = table();
        int total = table == null ? 0 : table.totalWeight();
        double chance = total > 0 ? entry.weight() * 100.0 / total : 0;

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Amount: " + entry.minimum()
                + (entry.maximum() != entry.minimum() ? "–" + entry.maximum() : ""));
        lore.add(String.format(Locale.ROOT, "<gray>Weight: %d (%.1f%% of this table)", entry.weight(), chance));
        if (edit()) {
            lore.add("");
            lore.add("<aqua>Left-click: edit");
            lore.add("<aqua>Shift-left-click: remove");
        }

        Material icon = entry.isCustom() ? Material.SPYGLASS : entry.material();
        String name = entry.isCustom() ? entry.customKey() : entry.material().name();
        return Icons.of(icon, "<yellow>" + name, lore);
    }

    @Override
    protected void onClick(LootEntry entry, InventoryClickEvent event) {
        if (!edit()) {
            return;
        }
        List<LootEntry> current = entries();
        int index = current.indexOf(entry);
        if (index < 0) {
            return;
        }
        if (event.isShiftClick() && event.isLeftClick()) {
            new ConfirmScreen(viewer, brand(), this, "<yellow>Remove this entry?",
                    List.of("<gray>" + (entry.isCustom() ? entry.customKey() : entry.material().name())
                                    + " leaves the table.",
                            "<dark_gray>Nothing else in the table changes."),
                    () -> {
                        List<LootEntry> updated = new ArrayList<>(current);
                        updated.remove(index);
                        replaceEntries(updated);
                        open();
                    }).open();
            return;
        }
        new LootEntryMenu(viewer, brand(), this, catalogue, customItems, tableName, index).open();
    }

    @Override
    protected void render() {
        super.render();
        LootTable table = table();
        if (table == null) {
            return;
        }
        if (edit()) {
            toolbar(2, Icons.of(Material.EMERALD, "<green>Add entry",
                            "<gray>A new STONE entry, weight 10.",
                            "<dark_gray>Opens straight into the editor."),
                    click -> {
                        List<LootEntry> updated = new ArrayList<>(table.entries());
                        updated.add(LootEntry.of(Material.STONE, 1).weight(10));
                        int newIndex = updated.size() - 1;
                        catalogue.define(tableName, table.tier(), table.fillPercent(), updated);
                        new LootEntryMenu(viewer, brand(), this, catalogue, customItems, tableName, newIndex)
                                .open();
                    });
            toolbar(4, Icons.of(Material.COMPARATOR, "<yellow>Tier: " + table.tier(),
                            "<gray>Which tier this table belongs to.",
                            "<dark_gray>Click to change."),
                    click -> new AmountChooser(viewer, brand(), this, "Tier", table.tier(), 1, 10,
                            value -> {
                                catalogue.define(tableName, value, table.fillPercent(), table.entries());
                                refresh();
                            }).open());
            toolbar(6, Icons.of(Material.PISTON, "<yellow>Fills: " + table.fillPercent() + "%",
                            "<gray>How much of a container this table fills.",
                            "<dark_gray>Click to change."),
                    click -> new AmountChooser(viewer, brand(), this, "Fill percent", table.fillPercent(), 1, 100,
                            value -> {
                                catalogue.define(tableName, table.tier(), value, table.entries());
                                refresh();
                            }).open());
        }
        toolbar(5, Icons.of(Material.OBSERVER, "<yellow>Test",
                        "<gray>Roll this table without touching the round."),
                click -> new LootTestMenu(viewer, brand(), this, catalogue, tableName).open());
    }

    /** Replaces the table's entries only, keeping its tier and fill percentage. */
    private void replaceEntries(List<LootEntry> newEntries) {
        LootTable table = table();
        if (table != null) {
            catalogue.define(tableName, table.tier(), table.fillPercent(), newEntries);
        }
    }

    @Override
    public String describe() {
        return "one loot table's entries, with the chance each actually rolls at";
    }
}
