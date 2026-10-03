package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.speedrun.manhunt.stats.HuntSummary;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * One player's speedrunning: a personal best per category, with how many runs and how many reached
 * the goal. A category opens its leaderboard; the header opens every run they raced.
 */
public final class SpeedrunStatsMenu extends PaginatedMenu<SpeedrunCategory> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    private final UUID whose;
    private final String whoseName;

    public SpeedrunStatsMenu(SpeedrunLobby lobby, Player viewer, Menu parent, UUID whose, String whoseName) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.whose = whose;
        this.whoseName = whoseName;
    }

    private SpeedrunHistory history() {
        return lobby.toolkit().map(SpeedrunToolkit::history).orElse(null);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Stats: " + SpeedrunScreens.text(whoseName));
    }

    @Override
    public String breadcrumb() {
        return "Stats";
    }

    @Override
    protected List<SpeedrunCategory> entries() {
        SpeedrunHistory history = history();
        if (history == null) {
            return List.of();
        }
        return history.categories().stream()
                .filter(category -> history.runsOf(whose).stream().anyMatch(run -> run.category().equals(category)))
                .toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BOOK, "<gray>No runs yet", "<gray>Race once and it shows here.");
    }

    @Override
    protected void decorate() {
        SpeedrunHistory history = history();
        List<SpeedrunRunRecord> runs = history == null ? List.of() : history.runsOf(whose);
        long finished = runs.stream().filter(SpeedrunRunRecord::completed).count();
        set(MenuLayout.HEADER_SUBJECT, Icons.head(whose, "<white>" + SpeedrunScreens.text(whoseName),
                "<gray>" + runs.size() + " run(s), " + finished + " reached the goal",
                "<gray>" + runs.stream().mapToInt(SpeedrunRunRecord::deaths).sum() + " death(s) in all",
                "",
                "<dark_gray>Click for every run."),
                click -> new SpeedrunHistoryMenu(lobby, viewer, this, whose, whoseName).open());
        // A game with sides keeps a standing per player — Manhunt's rating, wins on each side, catches.
        int slot = MenuLayout.HEADER_RIGHT;
        if (history != null) {
            for (String mode : history.modesWithStandings()) {
                history.standing(mode, whose).ifPresent(stats -> set(slot, Icons.of(Material.GOLDEN_HELMET,
                        "<gold>" + SpeedrunScreens.text(mode), standingLines(stats,
                                history.ranked(mode, SpeedrunBoard.RATING).indexOf(whose) + 1)),
                        click -> new SpeedrunStandingsMenu(lobby, viewer, this, mode, SpeedrunBoard.RATING).open()));
            }
        }
        super.decorate();
    }

    /** One player's standing in a game with sides, as lore. Only numbers and their own name in it. */
    static List<String> standingLines(PlayerStats stats, int rank) {
        return List.of(
                "<gray>Rating <white>" + Math.round(stats.rating()) + (rank > 0 ? " <gray>(#" + rank + ")" : ""),
                "<gray>" + stats.hunts() + " played: " + stats.runnerWins() + "/" + stats.runnerHunts()
                        + " won running, " + stats.hunterWins() + "/" + stats.hunterHunts() + " won hunting",
                "<gray>" + stats.catches() + " catch(es), caught " + stats.timesCaught() + " time(s)",
                "<gray>Longest run " + HuntSummary.clock(stats.bestSurvivalMillis())
                        + ", " + Math.round(stats.distance()) + " blocks covered",
                "",
                "<dark_gray>Click for the players' leaderboard.");
    }

    @Override
    protected ItemStack icon(SpeedrunCategory category) {
        SpeedrunHistory history = history();
        List<SpeedrunRunRecord> theirs = history.runsOf(whose).stream()
                .filter(run -> run.category().equals(category)).toList();
        String best = history.personalBest(whose, category).map(run -> SpeedrunTimerDisplay.plain(run.time()))
                .orElse("none yet");
        String record = history.record(category).map(run -> SpeedrunTimerDisplay.plain(run.time())).orElse("none yet");
        return Icons.of(category.isPractice() ? Material.TARGET : Material.CLOCK, "<white>" + SpeedrunScreens.text(category.label()),
                "<gray>Personal best: <gold>" + best,
                "<gray>Server record: <white>" + record,
                "<gray>" + theirs.size() + " run(s), "
                        + theirs.stream().filter(SpeedrunRunRecord::completed).count() + " finished",
                "",
                "<dark_gray>Click for the leaderboard.");
    }

    @Override
    protected void onClick(SpeedrunCategory category, InventoryClickEvent event) {
        new SpeedrunLeaderboardMenu(lobby, viewer, this, category).open();
    }
}
