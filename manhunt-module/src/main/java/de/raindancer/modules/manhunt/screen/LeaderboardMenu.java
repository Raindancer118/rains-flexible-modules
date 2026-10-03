package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.manhunt.stats.PlayerStats;
import de.raindancer.modules.manhunt.stats.StatsFormat;
import de.raindancer.modules.manhunt.stats.StatsStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Everybody this server's hunts remember, best first — by rating, wins, catches, survival or distance. */
public final class LeaderboardMenu extends PaginatedMenu<UUID> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;
    private StatsStore.Board board = StatsStore.Board.RATING;

    public LeaderboardMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Leaderboard — " + board.id());
    }

    @Override
    public String breadcrumb() {
        return "Leaderboard";
    }

    @Override
    protected List<UUID> entries() {
        return pages.services().chronicle().stats().ranked(board);
    }

    @Override
    protected ItemStack icon(UUID id) {
        PlayerStats stats = pages.services().chronicle().stats().get(id);
        int rank = entries().indexOf(id) + 1;
        return Icons.head(id, "<gold>#" + rank + " <white>" + MINI.escapeTags(stats.name()),
                "<gray>" + board.id() + ": <white>" + StatsFormat.value(board, stats),
                "<gray>" + stats.hunts() + " hunt(s), " + stats.wins() + " won.", "<dark_gray>Click for everything.");
    }

    @Override
    protected void onClick(UUID id, InventoryClickEvent event) {
        pages.stats(viewer, id, this);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BOOK, "<gray>No hunts on record yet", "<dark_gray>Play one!");
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.HOPPER, "<yellow>Sorted by " + board.id(),
                "<gray>Click for " + board.next().id() + "."), click -> {
                    board = board.next();
                    reopen();
                });
    }
}
