package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.ui.chat.Brand;
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

/**
 * Every loot table this module has defined, one per row of the grid.
 *
 * <h2>Left opens, right tests</h2>
 * Both are safe with nothing to confirm — opening reads a table and testing rolls against it without
 * touching the round, so unlike the pages further in, nothing here needs {@code ConfirmScreen}. The
 * right-click is why this page's lore says so: see {@code ScreenGrammarTest}'s note on a hidden click being
 * a feature only the person who wrote it knows about.
 */
public final class LootTablesMenu extends PaginatedMenu<LootTable> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final LootCatalogue catalogue;
    private final GameSession session;
    private final HungerGamesSettings settings;
    private final CustomItems customItems;

    public LootTablesMenu(Player viewer, Brand brand, Menu parent, LootCatalogue catalogue, GameSession session,
                          HungerGamesSettings settings, CustomItems customItems) {
        super(viewer, brand, parent);
        this.catalogue = catalogue;
        this.session = session;
        this.settings = settings;
        this.customItems = customItems;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Loot tables");
    }

    @Override
    public String breadcrumb() {
        return "Tables";
    }

    @Override
    protected List<LootTable> entries() {
        return catalogue.all();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No tables yet",
                "<gray>Nothing has been defined.",
                "<dark_gray>Go back and use \"New table\".");
    }

    @Override
    protected ItemStack icon(LootTable table) {
        boolean edit = LootMenu.canEdit(PermissionNodes.isAdmin(viewer), settings, session.phase());
        int total = table.totalWeight();

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + table.entries().size() + " entrie(s), tier " + table.tier()
                + ", fills " + table.fillPercent() + "% of the container");
        lore.add(total <= 0
                ? "<red>Total weight is zero — this table rolls nothing."
                : "<dark_gray>Total weight " + total);
        lore.add("");
        lore.add("<aqua>Left-click: open");
        lore.add("<aqua>Right-click: test");

        return Icons.of(iconMaterial(table.id()),
                (total <= 0 ? "<red>" : edit ? "<yellow>" : "<gray>") + table.id(), lore);
    }

    @Override
    protected void onClick(LootTable table, InventoryClickEvent event) {
        if (event.isRightClick()) {
            new LootTestMenu(viewer, brand(), this, catalogue, table.id()).open();
        } else {
            new LootTableMenu(viewer, brand(), this, catalogue, session, settings, customItems, table.id()).open();
        }
    }

    private static Material iconMaterial(String name) {
        if (name.contains("supply") || name.contains("drop")) {
            return Material.CHEST_MINECART;
        }
        if (name.contains("barrel")) {
            return Material.BARREL;
        }
        if (name.contains("shelf")) {
            return Material.CHISELED_BOOKSHELF;
        }
        return Material.CHEST;
    }

    @Override
    public String describe() {
        return "every loot table this module has defined";
    }
}
