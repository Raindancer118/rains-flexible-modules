package de.raindancer.modules.speedrun;

import de.raindancer.core.data.runs.LeaderboardMenu;
import de.raindancer.core.data.runs.Run;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Comparator;
import java.util.List;

/**
 * Core's leaderboard over the lobby's boards — one per goal, kind of seed, game, practice kit and
 * number of players, each player's best or every run, the record and the viewer's own best on top,
 * any other board of the lobby one click away. What the lobby adds: a run opens its whole summary, and
 * a game with sides has its players' board a click away.
 */
public final class SpeedrunLeaderboardMenu extends LeaderboardMenu {

    private final SpeedrunLobby lobby;
    private final SpeedrunHistory history;

    /**
     * @param category the board's category, or null for the one the next run would be filed in
     * @param players  how many raced, or 0 for whichever board of that category was played most
     */
    public SpeedrunLeaderboardMenu(SpeedrunLobby lobby, SpeedrunHistory history, Player viewer, Menu parent,
                                   SpeedrunCategory category, int players) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent, history.runs(),
                boardOf(history, category == null ? currentCategory(lobby) : category, players));
        this.lobby = lobby;
        this.history = history;
        // Every board of the lobby: another game, goal, seed or player count is a pick away.
        switchingWithin("");
    }

    /** Opens it where there is a history to show — a lobby without one has no leaderboard. */
    public static void open(SpeedrunLobby lobby, Player viewer, Menu parent, SpeedrunCategory category, int players) {
        lobby.toolkit().map(SpeedrunToolkit::history).ifPresent(history ->
                new SpeedrunLeaderboardMenu(lobby, history, viewer, parent, category, players).open());
    }

    /** The category the next run would be filed in — where a leaderboard opens. */
    static SpeedrunCategory currentCategory(SpeedrunLobby lobby) {
        SpeedrunSettings config = lobby.config();
        return lobby.splits().flatMap(SpeedrunSplitTracker::category).orElseGet(() -> new SpeedrunCategory(
                config.hasAdvancementGoal() ? config.advancementKey() : "",
                config.seedMode() == SpeedrunSeedMode.RANDOM ? SpeedrunSeedType.RANDOM : SpeedrunSeedType.SET,
                config.gameMode(), config.kit().isPractice() ? config.kit().name() : ""));
    }

    /**
     * The board to open: {@code players} racers' when asked for and played, otherwise the most played
     * board of {@code category}, otherwise the solo one.
     */
    static String boardOf(SpeedrunHistory history, SpeedrunCategory category, int players) {
        if (players > 0 && !history.leaderboard(category, players).isEmpty()) {
            return category.boardName(players);
        }
        return history.leaderboard(category, 0).stream()
                .collect(java.util.stream.Collectors.groupingBy(SpeedrunRunRecord::playerCount,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream().max(java.util.Map.Entry.<Integer, Long>comparingByValue()
                        .thenComparing(java.util.Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(most -> category.boardName(most.getKey()))
                .orElse(category.boardName(Math.max(1, players)));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("The best runs on this board, best first.",
                "One board per goal, seed, game, practice kit and number of players.",
                "Resumed and hand-edited runs only rank where the server says so.",
                "Click a run for every split of it.");
    }

    @Override
    protected void decorate() {
        String mode = SpeedrunCategory.modeOfBoard(category());
        if (history.modesWithStandings().contains(mode)) {
            toolbar(7, Icons.of(Material.PLAYER_HEAD, "<gold>Players", "<gray>Rating, wins, catches, survival.",
                            "<dark_gray>Click for the players' board."),
                    click -> new SpeedrunStandingsMenu(lobby, viewer, this, mode, SpeedrunBoard.RATING).open());
        }
        super.decorate();
    }

    @Override
    protected void onClick(Run run, InventoryClickEvent event) {
        history.byId(run.id()).ifPresent(record -> new SpeedrunRunSummaryMenu(lobby, record, viewer, this).open());
    }
}
