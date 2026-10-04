package de.raindancer.modules.speedrun;

import de.raindancer.core.data.runs.Run;
import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lobby's history, kept in Core's run history (Core's database) — every run with its whole
 * timeline, the per-player standings of games with sides beside it in {@code standings.yml}.
 */
class SpeedrunHistoryTest {

    static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    static final SpeedrunCategory DRAGON =
            new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.RANDOM, "", "");
    static final SpeedrunCategory DRAGON_SET =
            new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.SET, "", "");

    @TempDir
    Path folder;

    private Database database;

    private static long clock = 1_000;

    @BeforeEach
    void openDatabase() {
        database = Database.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    /** A finished, untouched run: the nether at {@code nether}, the goal at {@code total}. */
    static SpeedrunRunRecord run(SpeedrunCategory category, Duration nether, Duration total, UUID... racers) {
        return run(category, nether, total, true, List.of(), racers);
    }

    static SpeedrunRunRecord run(SpeedrunCategory category, Duration nether, Duration total, boolean completed,
                                 List<SpeedrunTimeline.Entry> extra, UUID... racers) {
        Map<UUID, String> who = new java.util.LinkedHashMap<>();
        for (UUID racer : racers) {
            who.put(racer, racer.equals(ALICE) ? "Alice" : "Bob");
        }
        List<SpeedrunTimeline.Entry> timeline = new ArrayList<>(extra);
        timeline.add(new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.SPLIT, nether, racers[0], "enter-nether"));
        timeline.add(new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.FINISH, total, null, "advancement:x"));
        return new SpeedrunRunRecord(UUID.randomUUID().toString(), category, clock++ * 1000, total, "advancement:x",
                completed, 42L, who, timeline, Map.of());
    }

    /** A fresh view over the same database and folder — what a restart gives. */
    private SpeedrunHistory history() {
        RunHistory runs = new RunHistory(database, "speedrun");
        runs.load();
        return new SpeedrunHistory(runs, new YamlStore(folder.resolve("standings.yml")), Runnable::run);
    }

    @Test
    @DisplayName("every run survives a restart, timeline, results and all — in Core's database")
    void survivesARestart() {
        SpeedrunHistory before = history();
        List<PlayerResult> results = List.of(new PlayerResult(ALICE, "Alice", true, true, false, 0, 1, 600_000, 812.5, 2),
                new PlayerResult(BOB, "Bob", false, false, false, 1, 0, 0, 30, 0));
        SpeedrunRunRecord plain = run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20),
                false, List.of(new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.DEATH, Duration.ofMinutes(3),
                        BOB, "Bob <red>fell</red> from a high place"),
                        new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.CAUGHT, Duration.ofMinutes(5), ALICE, "", BOB)),
                ALICE, BOB);
        SpeedrunRunRecord kept = new SpeedrunRunRecord(plain.id(), plain.category(), plain.startedAt(), plain.time(),
                plain.outcome(), plain.completed(), plain.seed(), plain.participants(), plain.timeline(),
                Map.of("enter-nether", "the Nether"), results, SpeedrunHistory.RUNNERS);
        before.add(kept);
        assertThat(before.flush()).isTrue();

        SpeedrunHistory after = history();

        assertThat(after.all()).hasSize(1);
        SpeedrunRunRecord read = after.all().getFirst();
        assertThat(read).isEqualTo(kept);
        assertThat(read.deaths()).isEqualTo(1);
        assertThat(read.nameOf(BOB)).isEqualTo("Bob");
    }

    @Test
    @DisplayName("Core's history holds the run as a timed run: its board, its splits by name, never practice beside real")
    void asCoreSeesIt() {
        SpeedrunHistory history = history();
        SpeedrunRunRecord solo = run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20), ALICE);
        history.add(solo);

        Run run = history.runs().byId(solo.id()).orElseThrow();
        assertThat(run.category()).isEqualTo(DRAGON.boardName(1));
        assertThat(run.time()).isEqualTo(Duration.ofMinutes(20));
        assertThat(run.lowerWins()).isTrue();
        assertThat(run.ranked()).isTrue();
        assertThat(run.players()).containsEntry(ALICE, "Alice");
        assertThat(run.splits()).containsEntry("Nether", Duration.ofMinutes(4).toMillis());
        assertThat(DRAGON.boardName(1)).isEqualTo("Race · Kill the dragon · Random seed · solo");
        assertThat(new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.SET, "manhunt", "EYES_OF_ENDER")
                .boardName(3)).isEqualTo("Manhunt · Kill the dragon · Set seed · Practice: Eyes of ender · 3 players");
    }

    @Test
    @DisplayName("standings that cannot be read are never overwritten by the next rated run")
    void unreadableStandingsAreLeftAlone() throws Exception {
        Path file = folder.resolve("standings.yml");
        Files.writeString(file, "standings: [this is: not: yaml");
        SpeedrunHistory history = history();

        history.importStandings("manhunt", Map.of());

        assertThat(Files.readString(file)).isEqualTo("standings: [this is: not: yaml");
    }

    @Nested
    @DisplayName("what ranks")
    class Ranking {

        @Test
        @DisplayName("personal best and record are the fastest finished runs of the category")
        void fastestCompleted() {
            SpeedrunHistory history = history();
            SpeedrunRunRecord aliceSlow = run(DRAGON, Duration.ofMinutes(5), Duration.ofMinutes(25), ALICE);
            SpeedrunRunRecord aliceFast = run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20), ALICE);
            SpeedrunRunRecord bobFastest = run(DRAGON, Duration.ofMinutes(3), Duration.ofMinutes(18), BOB);
            SpeedrunRunRecord bobUnfinished = run(DRAGON, Duration.ofMinutes(1), Duration.ofMinutes(2), false,
                    List.of(), BOB);
            List.of(aliceSlow, aliceFast, bobFastest, bobUnfinished).forEach(history::add);

            assertThat(history.personalBest(ALICE, DRAGON)).contains(aliceFast);
            assertThat(history.record(DRAGON)).contains(bobFastest);
            assertThat(history.bestSplit(DRAGON, "enter-nether")).contains(Duration.ofMinutes(3));
            assertThat(history.runs().byId(bobUnfinished.id()).orElseThrow().ranked()).isFalse();
        }

        @Test
        @DisplayName("set-seed and random-seed runs never share a record")
        void categoriesAreApart() {
            SpeedrunHistory history = history();
            history.add(run(DRAGON_SET, Duration.ofMinutes(1), Duration.ofMinutes(9), BOB));

            assertThat(history.record(DRAGON)).isEmpty();
            assertThat(history.record(DRAGON_SET)).isPresent();
        }

        @Test
        @DisplayName("a resumed or hand-edited run is kept, flagged, and off the board — unless the server says otherwise")
        void editedRunsAreFlagged() {
            SpeedrunHistory history = history();
            SpeedrunRunRecord resumed = run(DRAGON, Duration.ofMinutes(1), Duration.ofMinutes(5), true,
                    List.of(new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.RESUMED, Duration.ZERO, null, "")),
                    ALICE);
            SpeedrunRunRecord edited = run(DRAGON, Duration.ofMinutes(1), Duration.ofMinutes(6), true,
                    List.of(new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.CLOCK_EDIT, Duration.ZERO, null, "0:00")),
                    ALICE);
            history.add(resumed);
            history.add(edited);

            assertThat(resumed.resumed()).isTrue();
            assertThat(edited.clockEdited()).isTrue();
            assertThat(history.record(DRAGON)).isEmpty();
            assertThat(history.runs().leaderboard(DRAGON.boardName(1), RunHistory.Board.everyRun())).isEmpty();
            assertThat(history.all()).hasSize(2);

            history.editedRunsRank(true);
            assertThat(history.record(DRAGON)).contains(resumed);
            assertThat(history.runs().leaderboard(DRAGON.boardName(1), RunHistory.Board.everyRun()))
                    .extracting(Run::id).containsExactly(resumed.id(), edited.id());

            history.editedRunsRank(false);
            assertThat(history.runs().leaderboard(DRAGON.boardName(1), RunHistory.Board.everyRun())).isEmpty();
        }

        @Test
        @DisplayName("each number of players is its own board; the record stands over all of them")
        void byPlayerCount() {
            SpeedrunHistory history = history();
            SpeedrunRunRecord solo = run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20), ALICE);
            SpeedrunRunRecord duo = run(DRAGON, Duration.ofMinutes(3), Duration.ofMinutes(15), ALICE, BOB);
            history.add(solo);
            history.add(duo);

            assertThat(history.leaderboard(DRAGON, 1)).containsExactly(solo);
            assertThat(history.leaderboard(DRAGON, 2)).containsExactly(duo);
            assertThat(history.leaderboard(DRAGON, 0)).containsExactly(duo, solo);
            assertThat(history.record(DRAGON)).contains(duo);
        }

        @Test
        @DisplayName("runs are numbered in the order they were played, the first ever being 1")
        void numbering() {
            SpeedrunHistory history = history();
            SpeedrunRunRecord first = run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20), ALICE);
            SpeedrunRunRecord second = run(DRAGON, Duration.ofMinutes(3), Duration.ofMinutes(15), BOB);
            history.add(second);
            history.add(first);

            assertThat(history.numberOf(first)).isEqualTo(1);
            assertThat(history.byNumber(2)).contains(second);
            assertThat(history.newestFirst()).containsExactly(second, first);
            assertThat(history.runsOf(ALICE)).containsExactly(first);
            assertThat(history.played(42L)).isTrue();
        }
    }

    @Nested
    @DisplayName("comparing a split")
    class Comparing {

        @Test
        @DisplayName("against the viewer's personal best and the server record, and gold when faster than ever")
        void deltas() {
            SpeedrunHistory history = history();
            history.add(run(DRAGON, Duration.ofMinutes(5), Duration.ofMinutes(25), ALICE));
            history.add(run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(18), BOB));

            SpeedrunComparison comparison = SpeedrunComparison.of(history, DRAGON, ALICE, "enter-nether",
                    Duration.ofMinutes(3).plusSeconds(30));

            assertThat(comparison.vsPersonalBest()).contains(Duration.ofSeconds(-90));
            assertThat(comparison.vsRecord()).contains(Duration.ofSeconds(-30));
            assertThat(comparison.gold()).isTrue();
        }

        @Test
        @DisplayName("nothing to compare on the first run of a category, and that is not gold")
        void firstEver() {
            SpeedrunComparison comparison = SpeedrunComparison.of(history(), DRAGON, ALICE, "enter-nether",
                    Duration.ofMinutes(3));

            assertThat(comparison).isEqualTo(SpeedrunComparison.NOTHING_TO_COMPARE);
        }

        @Test
        @DisplayName("deltas read the way a split timer reads them")
        void deltaText() {
            assertThat(plain(SpeedrunComparison.delta(Duration.ofSeconds(-12)))).isEqualTo("-0:12");
            assertThat(plain(SpeedrunComparison.delta(Duration.ofSeconds(64)))).isEqualTo("+1:04");
            assertThat(plain(SpeedrunComparison.delta(Duration.ZERO))).isEqualTo("±0:00");
        }

        private String plain(net.kyori.adventure.text.Component component) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(component);
        }
    }

    @Test
    @DisplayName("a category is filed under one key and read back from it")
    void categoryKey() {
        SpeedrunCategory practice = new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.SET,
                "Manhunt", "EYES_OF_ENDER");

        assertThat(SpeedrunCategory.fromKey(practice.key())).contains(practice);
        assertThat(practice.isPractice()).isTrue();
        assertThat(practice.mode()).isEqualTo("manhunt");
        assertThat(SpeedrunCategory.fromKey("nonsense")).isEmpty();
    }
}
