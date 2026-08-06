package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The door into the module's loot tables — how many there are, whether the last load had problems, and the
 * one thing this page adds that {@link LootTablesMenu} does not: making a new one.
 *
 * <h2>Why there is no Save or Reload button here</h2>
 * The source plugin's {@code LootMainMenu} had both, because its own {@code LootTableRepository} kept edits
 * in memory until somebody pressed Save, with a backup and a manual reload from disk as the way back. Core's
 * {@link LootCatalogue} has no such staging area — every {@link LootCatalogue#define} call is the table, on
 * disk, immediately — so there is nothing to save and nothing to reload. That is a real simplification, not
 * an omission: two fewer buttons that used to exist only because the old framework had no registry of its
 * own to write straight through to.
 *
 * <h2>Why there is no Delete-table button anywhere in this module's loot screens</h2>
 * {@link LootCatalogue} exposes {@code define} and {@code defineIfAbsent} but no {@code undefine} — a
 * deliberate choice made where that class was written, not this one. This module's screens edit tables
 * through the catalogue and never around it, so a table cannot be removed from these pages. An owner who
 * needs one gone can define it empty; that costs a click more than a delete button would, in exchange for
 * never risking one keystroke on the loot table a supply drop is mid-round depending on.
 */
public final class LootMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final LootCatalogue catalogue;
    private final GameSession session;
    private final HungerGamesSettings settings;
    private final ChatPrompts prompts;
    private final CustomItems customItems;

    public LootMenu(Player viewer, Brand brand, Menu parent, LootCatalogue catalogue, GameSession session,
                    HungerGamesSettings settings, ChatPrompts prompts, CustomItems customItems) {
        super(viewer, brand, parent);
        this.catalogue = catalogue;
        this.session = session;
        this.settings = settings;
        this.prompts = prompts;
        this.customItems = customItems;
    }

    /**
     * Whether the viewer may change a loot table right now.
     *
     * <p>Pure so {@code LootMenuTest} can hold every combination against it without a server: the editor
     * being switched off entirely, being restricted to outside a running round, and the viewer simply not
     * being an admin are three different reasons to say no, and a gamemaster mid-round should see why the
     * editor is closed to them rather than a missing button.
     */
    public static boolean canEdit(boolean isAdmin, HungerGamesSettings settings, GamePhase phase) {
        if (!isAdmin || !settings.lootEditorEnabled()) {
            return false;
        }
        return settings.lootEditorAllowRuntimeEdits() || phase != GamePhase.RUNNING;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Loot");
    }

    @Override
    public String breadcrumb() {
        return "Loot";
    }

    @Override
    protected void render() {
        boolean edit = canEdit(PermissionNodes.isAdmin(viewer), settings, session.phase());
        List<String> problems = catalogue.problems();

        List<String> statusLore = new ArrayList<>();
        statusLore.add("<gray>" + catalogue.names().size() + " table(s)");
        statusLore.add(problems.isEmpty()
                ? "<green>The last load had no problems."
                : "<red>" + problems.size() + " problem(s) from the last load.");
        if (!edit) {
            statusLore.add(PermissionNodes.isAdmin(viewer)
                    ? "<red>Editing is off right now (the editor setting, or a running round)."
                    : "<dark_gray>Viewing and testing only — editing needs the admin node.");
        }
        band(MenuLayout.WHO, 4, Icons.of(Material.CHEST, "<gold>Loot", statusLore));

        band(MenuLayout.RULES, 3, Icons.of(Material.BOOKSHELF, "<yellow>Tables",
                        "<gray>Every table this module has defined.",
                        edit ? "<dark_gray>Open one to see and edit its entries." : "<dark_gray>Open one to see and test its entries."),
                click -> new LootTablesMenu(viewer, brand(), this, catalogue, session, settings, customItems)
                        .open());

        if (!problems.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("<red>From the last time the loot file was read:");
            for (String problem : problems) {
                lore.add("<dark_red>- " + problem);
            }
            band(MenuLayout.RULES, 5, Icons.of(Material.OBSERVER, "<red>Problems", lore));
        }

        if (edit) {
            band(MenuLayout.LAND, 4, Icons.of(Material.EMERALD, "<green>New table",
                            "<gray>Asks for a name, then opens it empty.",
                            "<dark_gray>You will be asked in chat."),
                    click -> askForNewTableName());
        }
    }

    private void askForNewTableName() {
        viewer.closeInventory();
        tell("<yellow>Type the new table's name in chat.");
        prompts.ask(viewer.getUniqueId(), "hungergames-loot", Duration.ofSeconds(60),
                typed -> {
                    String name = typed == null ? "" : typed.trim().toLowerCase(java.util.Locale.ROOT);
                    if (name.isEmpty()) {
                        tell("<red>Nothing usable was typed — no table created.");
                    } else if (catalogue.exists(name)) {
                        tell("<red>A table called \"" + name + "\" already exists.");
                    } else {
                        catalogue.define(name, 1, 50, List.of());
                        tell("<green>Table \"" + name + "\" created.");
                    }
                    open();
                },
                () -> tell("<red>Nothing was typed in time — no table created."));
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public String describe() {
        return "the loot tables this module has defined, and the way to a new one";
    }
}
