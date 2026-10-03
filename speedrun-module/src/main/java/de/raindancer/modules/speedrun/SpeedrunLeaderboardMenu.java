package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The fastest ranked runs, one category at a time: per goal, per kind of seed, per game, practice
 * on its own — and narrowed to a number of players. Each row opens that run's summary.
 */
public final class SpeedrunLeaderboardMenu extends PaginatedMenu<SpeedrunRunRecord> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** The player counts the filter cycles through; 0 is any. */
    static final int[] COUNTS = {SpeedrunHistory.Filter.ANY_COUNT, 1, 2, 3, 4};

    private final SpeedrunLobby lobby;
    private SpeedrunCategory category;
    private int count;
    /** The board as last read, so each row's place is not a fresh sort of the whole history. */
    private List<SpeedrunRunRecord> board = List.of();

    public SpeedrunLeaderboardMenu(SpeedrunLobby lobby, Player viewer, Menu parent, SpeedrunCategory category) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.category = category == null ? currentCategory(lobby) : category;
    }

    /** The category the next run would be filed in — where a leaderboard opens. */
    static SpeedrunCategory currentCategory(SpeedrunLobby lobby) {
        SpeedrunSettings config = lobby.config();
        return lobby.splits().flatMap(SpeedrunSplitTracker::category).orElseGet(() -> new SpeedrunCategory(
                config.hasAdvancementGoal() ? config.advancementKey() : "",
                config.seedMode() == SpeedrunSeedMode.RANDOM ? SpeedrunSeedType.RANDOM : SpeedrunSeedType.SET,
                config.gameMode(), config.kit().isPractice() ? config.kit().name() : ""));
    }

    private SpeedrunHistory history() {
        return lobby.toolkit().map(SpeedrunToolkit::history).orElse(null);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Leaderboard");
    }

    @Override
    public String breadcrumb() {
        return "Leaderboard";
    }

    @Override
    protected List<SpeedrunRunRecord> entries() {
        SpeedrunHistory history = history();
        board = history == null ? List.of() : history.leaderboard(new SpeedrunHistory.Filter(category, count));
        return board;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BOOK, "<gray>No ranked run here yet",
                "<gray>A finished run that reached its goal,", "<gray>untouched, lands on this board.");
    }

    @Override
    protected void decorate() {
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLDEN_HELMET, "<gold>" + SpeedrunScreens.text(category.label()),
                "<gray>" + (count == 0 ? "Any number of players" : count + " player(s)")));
        toolbar(1, Icons.of(Material.WRITABLE_BOOK, "<white>Goal",
                        "<gray>" + SpeedrunScreens.text(category.goal().isEmpty() ? "No goal" : SpeedrunAdvancementChooser.friendlyName(category.goal())),
                        "<dark_gray>Click for the next one."),
                click -> {
                    category = new SpeedrunCategory(next(goals(), category.goal()), category.seeds(),
                            category.mode(), category.practice());
                    refresh();
                });
        toolbar(2, Icons.of(Material.WHEAT_SEEDS, "<white>" + category.seeds().label(),
                        "<dark_gray>Click for the other kind."),
                click -> {
                    category = new SpeedrunCategory(category.goal(), category.seeds() == SpeedrunSeedType.RANDOM
                            ? SpeedrunSeedType.SET : SpeedrunSeedType.RANDOM, category.mode(), category.practice());
                    refresh();
                });
        toolbar(3, Icons.of(Material.NETHER_STAR, "<white>Game: " + SpeedrunScreens.text(category.mode().isEmpty() ? "Speedrun" : category.mode()),
                        "<dark_gray>Click for the next one."),
                click -> {
                    category = new SpeedrunCategory(category.goal(), category.seeds(), next(modes(), category.mode()),
                            category.practice());
                    refresh();
                });
        toolbar(5, Icons.of(Material.PLAYER_HEAD, "<white>" + (count == 0 ? "Any number of players" : count + " player(s)"),
                        "<dark_gray>Click for the next count."),
                click -> {
                    int at = 0;
                    for (int i = 0; i < COUNTS.length; i++) {
                        if (COUNTS[i] == count) {
                            at = i;
                        }
                    }
                    count = COUNTS[(at + 1) % COUNTS.length];
                    refresh();
                });
        SpeedrunHistory history = history();
        if (history != null && history.modesWithStandings().contains(category.mode())) {
            toolbar(7, Icons.of(Material.PLAYER_HEAD, "<gold>Players", "<gray>Rating, wins, catches, survival.",
                            "<dark_gray>Click for the players' board."),
                    click -> new SpeedrunStandingsMenu(lobby, viewer, this, category.mode(), SpeedrunBoard.RATING).open());
        }
        toolbar(6, Icons.of(category.isPractice() ? Material.TARGET : Material.DIAMOND_SWORD,
                        "<white>" + SpeedrunScreens.text(category.isPractice() ? "Practice: " + category.practice() : "Real runs"),
                        "<dark_gray>Click for the next one."),
                click -> {
                    category = new SpeedrunCategory(category.goal(), category.seeds(), category.mode(),
                            next(practices(), category.practice()));
                    refresh();
                });
        super.decorate();
    }

    private List<String> goals() {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(category.goal());
        seen.add(SpeedrunSettings.DRAGON_KILL_ADVANCEMENT);
        seenCategories().forEach(known -> seen.add(known.goal()));
        return new ArrayList<>(seen);
    }

    private List<String> modes() {
        Set<String> seen = new LinkedHashSet<>();
        seen.add("");
        SpeedrunModes.offered().forEach(mode -> seen.add(mode.id()));
        seenCategories().forEach(known -> seen.add(known.mode()));
        return new ArrayList<>(seen);
    }

    private List<String> practices() {
        List<String> all = new ArrayList<>();
        all.add("");
        for (SpeedrunPracticeKit kit : SpeedrunPracticeKit.values()) {
            if (kit.isPractice()) {
                all.add(kit.name());
            }
        }
        return all;
    }

    private List<SpeedrunCategory> seenCategories() {
        SpeedrunHistory history = history();
        return history == null ? List.of() : history.categories();
    }

    static String next(List<String> options, String current) {
        int at = options.indexOf(current);
        return options.isEmpty() ? current : options.get((at + 1) % options.size());
    }

    @Override
    protected ItemStack icon(SpeedrunRunRecord run) {
        int place = board.indexOf(run) + 1;
        Material medal = switch (place) {
            case 1 -> Material.GOLD_BLOCK;
            case 2 -> Material.IRON_BLOCK;
            case 3 -> Material.COPPER_BLOCK;
            default -> Material.PAPER;
        };
        return Icons.of(medal, "<gold>#" + place + " <white>" + SpeedrunTimerDisplay.plain(run.time()),
                "<gray>" + SpeedrunScreens.text(String.join(", ", run.participants().values())),
                "<gray>" + SpeedrunRunSummaryMenu.WHEN.format(java.time.Instant.ofEpochMilli(run.startedAt())),
                "<gray>Seed " + run.seed(),
                "",
                "<dark_gray>Click for every split.");
    }

    @Override
    protected void onClick(SpeedrunRunRecord run, InventoryClickEvent event) {
        new SpeedrunRunSummaryMenu(lobby, run, viewer, this).open();
    }
}
