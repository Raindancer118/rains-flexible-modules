package de.raindancer.modules.oneblock.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Upgrade;
import de.raindancer.modules.oneblock.rules.PurchaseRule;
import de.raindancer.modules.oneblock.service.IslandServer;
import de.raindancer.modules.oneblock.util.ProgressBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One dimension's upgrades: each with its level, what the next costs and what it does. A click buys the next level;
 * one the island cannot buy says why on the button. The other dimensions are a click away in the toolbar.
 */
public final class UpgradesMenu extends PaginatedMenu<Upgrade> implements IOneBlockScreen {

    private static final int[] DIMENSION_COLUMNS = {3, 4, 5};
    private static final int BAR = 8;

    private final OneBlockServices services;
    private final IslandServer islands;
    private final IslandDimension dimension;
    private final PurchaseRule purchases = new PurchaseRule();

    public UpgradesMenu(OneBlockServices services, IslandServer islands, Player viewer, Menu parent,
                        IslandDimension dimension) {
        super(viewer, islands.brand(), parent);
        this.services = services;
        this.islands = islands;
        this.dimension = dimension;
    }

    @Override
    protected Component title() {
        return Component.text("Upgrades · " + services.messages().raw(dimension.nameKey()).replaceAll("<[^>]+>", ""),
                NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Upgrades";
    }

    private Optional<Island> island() {
        return islands.book().ofPlayer(viewer.getUniqueId());
    }

    @Override
    protected List<Upgrade> entries() {
        return islands.currentContent().upgradesFor(dimension);
    }

    @Override
    protected ItemStack icon(Upgrade upgrade) {
        Optional<Island> island = island();
        int level = island.flatMap(it -> it.block(dimension)).map(block -> block.level(upgrade.id())).orElse(0);
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + what(upgrade));
        lore.add("<gray>Level <white>" + level + "<gray>/" + upgrade.maxLevel() + "  "
                + ProgressBar.mini(upgrade.maxLevel() == 0 ? 0 : level * 100 / upgrade.maxLevel(), BAR));
        if (level > 0) {
            lore.add("<gray>Now: <white>" + percent(upgrade.chanceAt(Math.min(level, upgrade.maxLevel()))) + chanceOf(upgrade));
        }
        Optional<Upgrade.Level> next = upgrade.level(level + 1);
        next.ifPresent(at -> {
            lore.add("<gray>Next: <white>" + percent(at.chance()) + chanceOf(upgrade));
            lore.add("<gray>Costs <light_purple>" + String.format("%,d", at.price()) + " shards");
        });
        lore.add("");
        ItemStack icon = Icons.of(upgrade.icon(), (level > 0 ? "<green>" : "<white>") + upgrade.name(), lore);
        if (island.isEmpty()) {
            return Icons.locked(icon, "You have no island.");
        }
        PurchaseRule.Verdict verdict = purchases.upgrade(islands.currentContent(), island.get(), viewer.getUniqueId(),
                dimension, upgrade.id());
        if (!verdict.allowed()) {
            return Icons.locked(icon, reason(verdict.refusal().orElse(""), island.get(), next));
        }
        lore.add("<yellow>Click to buy level " + (level + 1) + ".");
        return Icons.of(upgrade.icon(), (level > 0 ? "<green>" : "<white>") + upgrade.name(), lore);
    }

    @Override
    protected void onClick(Upgrade upgrade, InventoryClickEvent event) {
        IOneBlockScreen.tell(services.messages(), viewer,
                islands.islands().buy(viewer.getUniqueId(), dimension, upgrade.id()));
        refresh();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nothing to buy here", "<dark_gray>The server has no upgrades",
                "<dark_gray>for this dimension.");
    }

    @Override
    protected void decorate() {
        Island island = island().orElse(null);
        IslandDimension[] all = IslandDimension.values();
        for (int i = 0; i < all.length; i++) {
            IslandDimension other = all[i];
            boolean open = island != null && island.unlocked(other);
            String name = services.messages().raw(other.nameKey());
            toolbar(DIMENSION_COLUMNS[i], open && other != dimension, Icons.of(other.icon(),
                            (other == dimension ? "<green>" : "<white>") + name,
                            other == dimension ? "<gray>You are looking at these." : "<yellow>Click to see its upgrades."),
                    other == dimension ? "You are looking at these." : "Not unlocked yet.",
                    click -> new UpgradesMenu(services, islands, viewer, parent(), other).open());
        }
        if (island != null) {
            toolbar(7, Icons.of(Material.AMETHYST_SHARD, "<light_purple>" + String.format("%,d", island.shards())
                    + " shards", "<gray>Earned by breaking: one every", "<gray>"
                    + islands.currentContent().shardEvery() + " blocks, the team's together."), click -> { });
        }
    }

    private String what(Upgrade upgrade) {
        return switch (upgrade.kind()) {
            case BLOCK -> "Mixes in " + pretty(upgrade.block().name());
            case MOB -> "Spawns " + pretty(upgrade.mob().name()) + " on the block";
            case SMELT -> "Drops come out smelted";
            case AUTO_BREAK -> "The block breaks itself while you're online";
        };
    }

    private static String chanceOf(Upgrade upgrade) {
        return switch (upgrade.kind()) {
            case BLOCK, MOB -> " <gray>of breaks";
            case SMELT -> " <gray>of drops";
            case AUTO_BREAK -> " <gray>each second";
        };
    }

    private static String reason(String key, Island island, Optional<Upgrade.Level> next) {
        return switch (key) {
            case "oneblock.buy.maxed" -> "At its highest level.";
            case "oneblock.buy.too-poor" -> "Needs " + String.format("%,d", next.map(Upgrade.Level::price).orElse(0L)
                    - island.shards()) + " more shards.";
            case "oneblock.buy.locked" -> "This dimension is not unlocked.";
            case "oneblock.buy.not-yours" -> "Only the island's team buys upgrades.";
            default -> "Not for sale.";
        };
    }

    private static String percent(double chance) {
        return (chance == Math.rint(chance) ? String.valueOf((long) chance) : String.valueOf(chance)) + "%";
    }

    private static String pretty(String id) {
        String spaced = id.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @Override
    public String describe() {
        return "an island's upgrades for one dimension";
    }
}
