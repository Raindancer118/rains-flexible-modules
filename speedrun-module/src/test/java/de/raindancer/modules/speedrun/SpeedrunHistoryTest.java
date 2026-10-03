package de.raindancer.modules.speedrun;

import de.raindancer.core.data.store.YamlStore;
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

class SpeedrunHistoryTest {

    static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    static final SpeedrunCategory DRAGON =
            new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.RANDOM, "", "");
    static final SpeedrunCategory DRAGON_SET =
            new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.SET, "", "");

    @TempDir
    Path folder;

    private static long clock = 1_000;

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
        return new SpeedrunRunRecord(UUID.randomUUID().toString(), category, clock++, total, "advancement:x",
                completed, 42L, who, timeline, Map.of());
    }

    private SpeedrunHistory history() {
        return new SpeedrunHistory(new YamlStore(folder.resolve("history.yml")), Runnable::run);
    }

    @Test
    @DisplayName("every run survives a restart, timeline and all")
    void survivesARestart() {
        SpeedrunHistory before = history();
        SpeedrunRunRecord kept = run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20),
                false, List.of(new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.DEATH, Duration.ofMinutes(3),
                        BOB, "Bob fell from a high place")), ALICE, BOB);
        before.add(kept);

        SpeedrunHistory after = history();
        after.load();

        assertThat(after.all()).hasSize(1);
        SpeedrunRunRecord read = after.all().getFirst();
        assertThat(read).isEqualTo(kept);
        assertThat(read.deaths()).isEqualTo(1);
        assertThat(read.nameOf(BOB)).isEqualTo("Bob");
    }

    @Test
    @DisplayName("a history file that cannot be read is never overwritten by the next run")
    void unreadableFileIsLeftAlone() throws Exception {
        Path file = folder.resolve("history.yml");
        Files.writeString(file, "runs: [this is: not: yaml");
        SpeedrunHistory history = history();
        history.load();

        history.add(run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20), ALICE));

        assertThat(Files.readString(file)).isEqualTo("runs: [this is: not: yaml");
        assertThat(history.all()).hasSize(1);
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
            assertThat(history.all()).hasSize(2);

            history.editedRunsRank(true);
            assertThat(history.record(DRAGON)).contains(resumed);
        }

        @Test
        @DisplayName("a leaderboard can be narrowed to a number of players")
        void byPlayerCount() {
            SpeedrunHistory history = history();
            SpeedrunRunRecord solo = run(DRAGON, Duration.ofMinutes(4), Duration.ofMinutes(20), ALICE);
            SpeedrunRunRecord duo = run(DRAGON, Duration.ofMinutes(3), Duration.ofMinutes(15), ALICE, BOB);
            history.add(solo);
            history.add(duo);

            assertThat(history.leaderboard(new SpeedrunHistory.Filter(DRAGON, 0))).containsExactly(duo, solo);
            assertThat(history.leaderboard(new SpeedrunHistory.Filter(DRAGON, 1))).containsExactly(solo);
            assertThat(history.leaderboard(new SpeedrunHistory.Filter(DRAGON, 2))).containsExactly(duo);
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
