package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;
import de.raindancer.modules.speedrun.manhunt.stats.StatsFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The players' leaderboard of a game with sides — by rating, wins, catches, longest survival or
 * distance — beside the runs' leaderboard, from the same history. Each player opens their stats.
 */
public final class SpeedrunStandingsMenu extends PaginatedMenu<UUID> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    private final String mode;
    private SpeedrunBoard board;

    public SpeedrunStandingsMenu(SpeedrunLobby lobby, Player viewer, Menu parent, String mode, SpeedrunBoard board) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.mode = mode;
        this.board = board == null ? SpeedrunBoard.RATING : board;
    }

    private SpeedrunHistory history() {
        return lobby.toolkit().map(SpeedrunToolkit::history).orElse(null);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Players — " + SpeedrunScreens.text(board.label()));
    }

    @Override
    public String breadcrumb() {
        return "Players";
    }

    @Override
    protected List<UUID> entries() {
        SpeedrunHistory history = history();
        return history == null ? List.of() : history.ranked(mode, board);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BOOK, "<gray>Nobody rated yet", "<gray>A finished hunt rates everybody in it.");
    }

    @Override
    protected void decorate() {
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLDEN_HELMET, "<gold>" + SpeedrunScreens.text(mode)
                + " — " + board.label(), "<gray>Click a player for their stats."));
        toolbar(4, Icons.of(Material.COMPARATOR, "<white>Sorted by " + board.label(), "<dark_gray>Click for the next."),
                click -> {
                    board = board.next();
                    refresh();
                });
        super.decorate();
    }

    @Override
    protected ItemStack icon(UUID id) {
        PlayerStats stats = history().standing(mode, id).orElse(PlayerStats.fresh("somebody"));
        int rank = entries().indexOf(id) + 1;
        return Icons.head(id, "<gold>#" + rank + " <white>" + SpeedrunScreens.text(stats.name()),
                "<gray>" + board.label() + ": <white>" + SpeedrunScreens.text(StatsFormat.value(board, stats)),
                "<gray>Rating " + Math.round(stats.rating()) + ", " + stats.hunts() + " played",
                "", "<dark_gray>Click for their stats.");
    }

    @Override
    protected void onClick(UUID id, InventoryClickEvent event) {
        String name = history().standing(mode, id).map(PlayerStats::name).orElse("somebody");
        new SpeedrunStatsMenu(lobby, viewer, this, id, name).open();
    }
}
