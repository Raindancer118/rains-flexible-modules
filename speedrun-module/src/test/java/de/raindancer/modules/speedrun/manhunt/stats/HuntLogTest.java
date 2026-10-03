package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.speedrun.SpeedrunBoard;
import de.raindancer.modules.speedrun.SpeedrunCategory;
import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunRunRecord;
import de.raindancer.modules.speedrun.SpeedrunSeedType;
import de.raindancer.modules.speedrun.SpeedrunTimeline;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The record of a hunt: each player's numbers, kept as it is played, what happened to whom on the
 * run's own timeline, and — at the end — everybody's results in the one speedrun history, where they
 * move the players' standings.
 */
@DisplayName("the record of a hunt")
class HuntLogTest {

    static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    static final UUID SECOND = UUID.nameUUIDFromBytes("second".getBytes());
    static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());
    static final UUID OTHER = UUID.nameUUIDFromBytes("other".getBytes());
    private static final SpeedrunCategory MANHUNT =
            new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.RANDOM, ManhuntMode.ID, "");

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final SpeedrunTimeline timeline = new SpeedrunTimeline();
    private HuntLog log;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        Map<UUID, String> runners = new LinkedHashMap<>();
        runners.put(RUNNER, "Runner");
        runners.put(SECOND, "Second");
        Map<UUID, String> hunters = new LinkedHashMap<>();
        hunters.put(HUNTER, "Hunter");
        hunters.put(OTHER, "Other");
        log = new HuntLog(now::get, runners, hunters, timeline, () -> Duration.ofMillis(now.get() - 1_000_000));
    }

    private void minutes(int minutes) {
        now.addAndGet(minutes * 60_000L);
    }

    /** Two Runners, both caught by Hunter; the Hunters win at 20:00. */
    private SpeedrunRunRecord huntersWin() {
        minutes(4);
        log.portal(SECOND);
        log.travelled(SECOND, 900);
        log.travelled(HUNTER, 1200);
        minutes(6);
        log.hunterDied(OTHER, "Other", SECOND, "Second");
        log.lifeLost(RUNNER, "Runner", HUNTER, "Hunter", 1);
        minutes(2);
        log.caught(RUNNER, "Runner", HUNTER, "Hunter");
        minutes(8);
        log.caught(SECOND, "Second", HUNTER, "Hunter");
        return run(SpeedrunHistory.HUNTERS, "manhunt:caught");
    }

    private SpeedrunRunRecord run(String winner, String reason) {
        Map<UUID, String> names = Map.of(RUNNER, "Runner", SECOND, "Second", HUNTER, "Hunter", OTHER, "Other");
        return new SpeedrunRunRecord(UUID.randomUUID().toString(), MANHUNT, 0, Duration.ofMillis(log.elapsed()), reason,
                reason.startsWith("advancement:"), 1, names, timeline.entries(), Map.of(), log.results(winner), winner);
    }

    @Test
    @DisplayName("the finished record has who played which side, who won, and everybody's numbers")
    void record() {
        SpeedrunRunRecord record = huntersWin();

        assertThat(record.time()).isEqualTo(Duration.ofMinutes(20));
        PlayerResult hunter = record.resultOf(HUNTER).orElseThrow();
        assertThat(hunter.won()).isTrue();
        assertThat(hunter.catches()).isEqualTo(2);
        assertThat(hunter.distance()).isEqualTo(1200);
        PlayerResult runner = record.resultOf(RUNNER).orElseThrow();
        assertThat(runner.caught()).isTrue();
        assertThat(runner.deaths()).isEqualTo(2);
        assertThat(runner.survivedMillis()).isEqualTo(12 * 60_000L);
        assertThat(record.resultOf(SECOND).orElseThrow().portals()).isEqualTo(1);
        assertThat(record.resultOf(OTHER).orElseThrow().deaths()).isEqualTo(1);
    }

    @Test
    @DisplayName("what happened to whom is on the run's own timeline, against the run's clock")
    void timeline() {
        huntersWin();

        assertThat(timeline.entries()).extracting(SpeedrunTimeline.Entry::kind).containsExactly(
                SpeedrunTimeline.Kind.HUNTER_DIED, SpeedrunTimeline.Kind.LIFE_LOST,
                SpeedrunTimeline.Kind.CAUGHT, SpeedrunTimeline.Kind.CAUGHT);
        SpeedrunTimeline.Entry caught = timeline.of(SpeedrunTimeline.Kind.CAUGHT).getFirst();
        assertThat(caught.who()).isEqualTo(RUNNER);
        assertThat(caught.other()).isEqualTo(HUNTER);
        assertThat(caught.at()).isEqualTo(Duration.ofMinutes(12));
        assertThat(timeline.of(SpeedrunTimeline.Kind.LIFE_LOST).getFirst().detail()).isEqualTo("1");
    }

    @Test
    @DisplayName("the summary: who caught whom and when, and the MVPs")
    void summary() {
        HuntSummary summary = HuntSummary.of(huntersWin());

        assertThat(summary.winner()).isEqualTo(HuntSummary.Winner.HUNTERS);
        assertThat(summary.catches()).extracting(HuntSummary.Catch::runnerName).containsExactly("Runner", "Second");
        assertThat(summary.catches().getFirst().byName()).isEqualTo("Hunter");
        assertThat(summary.catches().getFirst().atMillis()).isEqualTo(12 * 60_000L);
        assertThat(summary.hunterMvp()).hasValueSatisfying(mvp -> assertThat(mvp.name()).isEqualTo("Hunter"));
        assertThat(summary.runnerMvp()).hasValueSatisfying(mvp -> assertThat(mvp.name()).isEqualTo("Second"));
        assertThat(summary.explorer()).hasValueSatisfying(mvp -> assertThat(mvp.name()).isEqualTo("Hunter"));
    }

    @Test
    @DisplayName("Runners still running at a Runners' win survived the whole hunt, and won")
    void runnersWin() {
        minutes(30);
        List<PlayerResult> results = log.results(SpeedrunHistory.RUNNERS);

        PlayerResult runner = results.stream().filter(r -> r.id().equals(RUNNER)).findFirst().orElseThrow();
        assertThat(runner.survivedMillis()).isEqualTo(30 * 60_000L);
        assertThat(runner.won()).isTrue();
        assertThat(results.stream().filter(r -> r.id().equals(HUNTER)).findFirst().orElseThrow().won()).isFalse();
    }

    @Test
    @DisplayName("a side change is on the record, and a side change and a late join decide the side they are scored on")
    void sidesMove() {
        log.sideChanged(SECOND, "Second", false);
        UUID late = UUID.randomUUID();
        log.joined(late, "Late", true);
        List<PlayerResult> results = log.results(SpeedrunHistory.RUNNERS);

        assertThat(results.stream().filter(r -> r.id().equals(SECOND)).findFirst().orElseThrow().runner()).isFalse();
        assertThat(results.stream().filter(r -> r.id().equals(late)).findFirst().orElseThrow().runner()).isTrue();
        assertThat(timeline.of(SpeedrunTimeline.Kind.SIDE_CHANGED)).singleElement()
                .satisfies(entry -> assertThat(entry.detail()).isEqualTo("hunting"));
    }

    @Test
    @DisplayName("somebody who left is not scored at all")
    void leftIsNotScored() {
        log.left(OTHER, "Other");

        assertThat(log.results(SpeedrunHistory.HUNTERS)).noneMatch(result -> result.id().equals(OTHER));
    }

    @Test
    @DisplayName("stats add up across hunts and the ratings move — in the one history, saved and read back")
    void standings() {
        SpeedrunHistory history = new SpeedrunHistory(new YamlStore(directory.resolve("history.yml")), Runnable::run);
        history.add(huntersWin(), true);

        SpeedrunHistory reread = new SpeedrunHistory(new YamlStore(directory.resolve("history.yml")), Runnable::run);
        reread.load();
        StatsStore stats = new StatsStore(() -> reread, ManhuntMode.ID);
        PlayerStats hunter = stats.get(HUNTER);
        assertThat(hunter.name()).isEqualTo("Hunter");
        assertThat(hunter.hunterWins()).isEqualTo(1);
        assertThat(hunter.catches()).isEqualTo(2);
        assertThat(hunter.rating()).isGreaterThan(Rating.START);
        PlayerStats runner = stats.get(RUNNER);
        assertThat(runner.timesCaught()).isEqualTo(1);
        assertThat(runner.rating()).isLessThan(Rating.START);
        assertThat(stats.get(HUNTER).rating() + stats.get(OTHER).rating()
                + runner.rating() + stats.get(SECOND).rating()).isCloseTo(4 * Rating.START, within(1e-6));
        assertThat(stats.byName("hUnTeR")).contains(HUNTER);
        assertThat(stats.top(SpeedrunBoard.CATCHES, 1)).extracting(PlayerStats::name).containsExactly("Hunter");
        assertThat(reread.all()).singleElement().satisfies(run -> {
            assertThat(run.players()).hasSize(4);
            assertThat(run.winner()).isEqualTo(SpeedrunHistory.HUNTERS);
            assertThat(run.timeline()).isEqualTo(timeline.entries());
        });
    }

    @Test
    @DisplayName("a hunt nobody won counts as played, and moves no rating")
    void nobodysWin() {
        SpeedrunHistory history = new SpeedrunHistory(null, Runnable::run);
        history.add(run("", "admin-reset"), true);
        StatsStore stats = new StatsStore(() -> history, ManhuntMode.ID);

        assertThat(stats.get(RUNNER).hunts()).isEqualTo(1);
        assertThat(stats.get(RUNNER).rating()).isEqualTo(Rating.START);
    }

    @Test
    @DisplayName("with stats off, a hunt is kept in the history and moves nobody's standing")
    void unrated() {
        SpeedrunHistory history = new SpeedrunHistory(null, Runnable::run);
        history.add(huntersWin(), false);

        assertThat(history.all()).hasSize(1);
        assertThat(new StatsStore(() -> history, ManhuntMode.ID).has(HUNTER)).isFalse();
    }

    @Test
    @DisplayName("runs are numbered in the order they were played, for /manhunt summary")
    void numbering() {
        SpeedrunHistory history = new SpeedrunHistory(null, Runnable::run);
        SpeedrunRunRecord first = huntersWin();
        history.add(first, false);

        assertThat(history.numberOf(first)).isEqualTo(1);
        assertThat(history.byNumber(1)).contains(first);
        assertThat(history.byNumber(2)).isEmpty();
    }
}
