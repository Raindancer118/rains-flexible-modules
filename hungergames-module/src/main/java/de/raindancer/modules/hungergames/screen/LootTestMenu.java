package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.content.loot.LootFiller;
import de.raindancer.core.content.loot.LootRoll;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Rolling a table without touching the round.
 *
 * <h2>What the source plugin's "test chest" is not here</h2>
 * The source could drop a filled chest at the tester's feet. Doing that for real needs a world-block seam —
 * something to place and configure a container, the same shape as {@code BorderService.WorldBorderTarget} or
 * {@code SponsorBeaconService.BeaconBlock} — and none exists for this yet; wiring one is outside this
 * screen's lane. So this page keeps the two tests that need nothing but an inventory: rolling the numbers,
 * and handing the tester the result straight into their own inventory through Core's {@link LootFiller} —
 * which is also how a screen gives away real items without a {@code new ItemStack(} of its own.
 */
public final class LootTestMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int[] ROLL_COUNTS = {1, 10, 50};

    private final LootCatalogue catalogue;
    private final LootFiller filler;
    private final String tableName;
    private final Random random = new Random();

    private Map<String, Integer> lastResult = Map.of();
    private int lastRolls;

    public LootTestMenu(Player viewer, Brand brand, Menu parent, LootCatalogue catalogue, String tableName) {
        this(viewer, brand, parent, catalogue, null, tableName);
    }

    /** @param filler required only for "give me test loot"; may be {@code null} to hide that button */
    public LootTestMenu(Player viewer, Brand brand, Menu parent, LootCatalogue catalogue, LootFiller filler,
                        String tableName) {
        super(viewer, brand, parent);
        this.catalogue = catalogue;
        this.filler = filler;
        this.tableName = tableName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Test — <white>" + tableName);
    }

    @Override
    public String breadcrumb() {
        return "Test";
    }

    private LootTable table() {
        return catalogue.byName(tableName).orElse(null);
    }

    @Override
    protected void render() {
        LootTable table = table();
        if (table == null) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>Table gone",
                    "<gray>\"" + tableName + "\" no longer exists."));
            return;
        }
        band(MenuLayout.WHO, 4, Icons.of(Material.OBSERVER, "<yellow>Test — " + tableName,
                "<gray>Rolling here changes nothing about the round.",
                table.totalWeight() <= 0 ? "<red>Total weight is zero — nothing would roll." : ""));

        int column = 2;
        for (int rolls : ROLL_COUNTS) {
            int finalRolls = rolls;
            band(MenuLayout.RULES, column++, Icons.of(Material.LIME_DYE, "<green>Roll " + rolls + "×",
                            "<gray>Simulates " + rolls + " draw(s) and shows the spread."),
                    click -> simulate(table, finalRolls));
        }

        if (filler != null) {
            band(MenuLayout.RULES, 6, Icons.of(Material.BUNDLE, "<yellow>Give me test loot",
                            "<gray>Fills your inventory as if this table", "<gray>had just filled a container."),
                    click -> giveTestLoot(table));
        }

        if (!lastResult.isEmpty()) {
            int slot = 1;
            for (Map.Entry<String, Integer> result : lastResult.entrySet()) {
                if (slot > 7) {
                    break;
                }
                double percent = result.getValue() * 100.0 / Math.max(1, lastRolls);
                band(MenuLayout.LAND, slot++, Icons.of(Material.PAPER, "<white>" + result.getKey(),
                        String.format(Locale.ROOT, "<gray>%d× (%.1f%%)", result.getValue(), percent)));
            }
        }
    }

    private void simulate(LootTable table, int rolls) {
        List<LootRoll> drawn = table.roll(rolls, random);
        if (drawn.isEmpty()) {
            tell("<red>Nothing rolled — the table is empty or its total weight is zero.");
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LootRoll roll : drawn) {
            String key = roll.entry().isCustom() ? roll.entry().customKey() : roll.entry().material().name();
            counts.merge(key, 1, Integer::sum);
        }
        lastResult = counts;
        lastRolls = drawn.size();
        refresh();
    }

    private void giveTestLoot(LootTable table) {
        int filled = filler.fill(viewer.getInventory(), table, random);
        tell(filled > 0
                ? "<green>" + filled + " test item(s) added to your inventory."
                : "<red>Nothing was given — the table is empty or its total weight is zero.");
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public String describe() {
        return "rolling a loot table without touching the round";
    }
}
