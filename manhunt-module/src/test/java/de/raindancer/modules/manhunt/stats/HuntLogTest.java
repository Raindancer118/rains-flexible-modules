package de.raindancer.modules.manhunt.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** What a hunt leaves behind: its timeline, its summary, everybody's numbers and the ratings. */
@DisplayName("the record of a hunt")
class HuntLogTest {

    static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    static final UUID SECOND = UUID.nameUUIDFromBytes("second".getBytes());
    static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());
    static final UUID OTHER = UUID.nameUUIDFromBytes("other".getBytes());

    private final AtomicLong now = new AtomicLong(1_000_000);
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
        log = new HuntLog(now::get, runners, hunters);
    }

    private void minutes(int minutes) {
        now.addAndGet(minutes * 60_000L);
    }

    /** Two Runners, both caught by Hunter, Second in the Nether first; the Hunters win at 20:00. */
    private HuntRecord huntersWin() {
        minutes(4);
        log.milestone(Milestone.NETHER, SECOND, "Second");
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
        return log.finish(7, "manhunt:caught", HuntRecord.Winner.HUNTERS);
    }

    @Test
    @DisplayName("each milestone counts once — the first to reach it, at the time they did")
    void milestonesOnce() {
        minutes(3);
        assertThat(log.milestone(Milestone.NETHER, RUNNER, "Runner")).isTrue();
        minutes(1);
        assertThat(log.milestone(Milestone.NETHER, SECOND, "Second")).isFalse();

        assertThat(log.milestoneAt(Milestone.NETHER)).contains(180_000L);
    }

    @Test
    @DisplayName("the finished record has who played which side, who won, and everybody's numbers")
    void record() {
        HuntRecord record = huntersWin();

        assertThat(record.number()).isEqualTo(7);
        assertThat(record.durationMillis()).isEqualTo(20 * 60_000L);
        PlayerResult hunter = record.player(HUNTER).orElseThrow();
        assertThat(hunter.won()).isTrue();
        assertThat(hunter.catches()).isEqualTo(2);
        assertThat(hunter.distance()).isEqualTo(1200);
        PlayerResult runner = record.player(RUNNER).orElseThrow();
        assertThat(runner.caught()).isTrue();
        assertThat(runner.deaths()).isEqualTo(2);
        assertThat(runner.survivedMillis()).isEqualTo(12 * 60_000L);
        assertThat(record.player(SECOND).orElseThrow().portals()).isEqualTo(1);
        assertThat(record.player(OTHER).orElseThrow().deaths()).isEqualTo(1);
        assertThat(record.events()).first().extracting(TimelineEvent::kind).isEqualTo(TimelineEvent.Kind.STARTED);
        assertThat(record.events()).last().extracting(TimelineEvent::kind).isEqualTo(TimelineEvent.Kind.FINISHED);
    }

    @Test
    @DisplayName("the summary: who caught whom and when, the splits, and the MVPs")
    void summary() {
        HuntSummary summary = HuntSummary.of(huntersWin());

        assertThat(summary.catches()).extracting(HuntSummary.Catch::runnerName).containsExactly("Runner", "Second");
        assertThat(summary.catches().getFirst().byName()).isEqualTo("Hunter");
        assertThat(summary.catches().getFirst().atMillis()).isEqualTo(12 * 60_000L);
        assertThat(summary.splits()).singleElement().satisfies(split -> {
            assertThat(split.milestone()).isEqualTo(Milestone.NETHER);
            assertThat(split.whoName()).isEqualTo("Second");
        });
        assertThat(summary.hunterMvp()).hasValueSatisfying(mvp -> assertThat(mvp.name()).isEqualTo("Hunter"));
        assertThat(summary.runnerMvp()).hasValueSatisfying(mvp -> assertThat(mvp.name()).isEqualTo("Second"));
        assertThat(summary.explorer()).hasValueSatisfying(mvp -> assertThat(mvp.name()).isEqualTo("Hunter"));
    }

    @Test
    @DisplayName("Runners still running at a Runners' win survived the whole hunt, and won")
    void runnersWin() {
        minutes(30);
        HuntRecord record = log.finish(1, "advancement:minecraft:end/kill_dragon", HuntRecord.Winner.RUNNERS);

        assertThat(record.player(RUNNER).orElseThrow().survivedMillis()).isEqualTo(30 * 60_000L);
        assertThat(record.player(RUNNER).orElseThrow().won()).isTrue();
        assertThat(record.player(HUNTER).orElseThrow().won()).isFalse();
    }

    @Test
    @DisplayName("a side change and a late join are on the record, and decide the side they are scored on")
    void sidesMove() {
        log.sideChanged(SECOND, "Second", false);
        UUID late = UUID.randomUUID();
        log.joined(late, "Late", true);
        HuntRecord record = log.finish(2, "advancement:x", HuntRecord.Winner.RUNNERS);

        assertThat(record.player(SECOND).orElseThrow().runner()).isFalse();
        assertThat(record.player(late).orElseThrow().runner()).isTrue();
        assertThat(record.events()).extracting(TimelineEvent::kind)
                .contains(TimelineEvent.Kind.SIDE_CHANGED, TimelineEvent.Kind.JOINED);
    }

    @Test
    @DisplayName("somebody who left is not scored at all")
    void leftIsNotScored() {
        log.left(OTHER, "Other");

        HuntRecord record = log.finish(3, "manhunt:caught", HuntRecord.Winner.HUNTERS);

        assertThat(record.player(OTHER)).isEmpty();
    }

    @Test
    @DisplayName("stats add up across hunts and the ratings move, saved and read back")
    void statsStore() {
        StatsStore stats = new StatsStore(directory.resolve("stats.yml"));
        stats.record(huntersWin());

        StatsStore reread = new StatsStore(directory.resolve("stats.yml"));
        PlayerStats hunter = reread.get(HUNTER);
        assertThat(hunter.name()).isEqualTo("Hunter");
        assertThat(hunter.hunterWins()).isEqualTo(1);
        assertThat(hunter.catches()).isEqualTo(2);
        assertThat(hunter.rating()).isGreaterThan(Rating.START);
        PlayerStats runner = reread.get(RUNNER);
        assertThat(runner.timesCaught()).isEqualTo(1);
        assertThat(runner.rating()).isLessThan(Rating.START);
        assertThat(reread.get(HUNTER).rating() + reread.get(OTHER).rating()
                + runner.rating() + reread.get(SECOND).rating()).isCloseTo(4 * Rating.START, within(1e-6));
        assertThat(reread.byName("hUnTeR")).contains(HUNTER);
        assertThat(reread.top(StatsStore.Board.CATCHES, 1)).extracting(PlayerStats::name).containsExactly("Hunter");
    }

    @Test
    @DisplayName("a hunt nobody won counts as played, and moves no rating")
    void nobodysWin() {
        StatsStore stats = new StatsStore(directory.resolve("stats.yml"));
        stats.record(log.finish(4, "admin-reset", HuntRecord.Winner.NOBODY));

        assertThat(stats.get(RUNNER).hunts()).isEqualTo(1);
        assertThat(stats.get(RUNNER).rating()).isEqualTo(Rating.START);
    }

    @Test
    @DisplayName("past hunts are kept with their whole timeline, the newest first, only as many as asked")
    void history() {
        HistoryStore history = new HistoryStore(directory.resolve("hunts.yml"));
        HuntRecord first = huntersWin();
        history.add(first, 2);
        history.add(withNumber(first, 8), 2);
        history.add(withNumber(first, 9), 2);

        HistoryStore reread = new HistoryStore(directory.resolve("hunts.yml"));
        assertThat(reread.all()).extracting(HuntRecord::number).containsExactly(9, 8);
        assertThat(reread.find(9).orElseThrow().events()).isEqualTo(first.events());
        assertThat(reread.find(9).orElseThrow().players()).isEqualTo(first.players());
        assertThat(reread.nextNumber()).isEqualTo(10);
    }

    @Test
    @DisplayName("with nothing kept, nothing is written and numbering still goes on")
    void keepNone() {
        HistoryStore history = new HistoryStore(directory.resolve("hunts.yml"));
        history.add(huntersWin(), 0);

        assertThat(history.all()).isEmpty();
        assertThat(history.nextNumber()).isEqualTo(8);
    }

    private static HuntRecord withNumber(HuntRecord record, int number) {
        return new HuntRecord(number, record.startedAt(), record.durationMillis(), record.reason(),
                record.winner(), record.players(), record.events());
    }
}
