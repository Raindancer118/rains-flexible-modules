package de.raindancer.modules.manhunt.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is remembered about a finished hunt, and what a summary makes of every one recorded so far.
 *
 * <p>Against a real, temporary SQLite file rather than a mock of {@link Database} — the same choice
 * {@code FarmWorldStateTest} makes for its own table, and for the same reason: {@link Database} is the
 * leaf that already owns transactions, rollback and the busy-timeout retry, so mocking it would only
 * prove this class calls a mock correctly, not that a hunt survives being written and read back.
 */
class HuntHistoryTest {

    @TempDir
    Path serverDirectory;

    private Database database;
    private HuntHistory history;

    private final UUID runnerA = UUID.randomUUID();
    private final UUID runnerB = UUID.randomUUID();
    private final UUID hunterA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        database = Database.open(serverDirectory.resolve("history.db"), HuntHistory.SCHEMA, () -> false);
        history = new HuntHistory(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Nested
    @DisplayName("who won, worked out from the reason")
    class WinnerForReason {

        @Test
        @DisplayName("a portal exit or an advancement is the Runners'")
        void runnersWin() {
            assertThat(HuntHistory.Winner.forReason("portal-exit")).isEqualTo(HuntHistory.Winner.RUNNERS);
            assertThat(HuntHistory.Winner.forReason("advancement:end/kill_dragon"))
                    .isEqualTo(HuntHistory.Winner.RUNNERS);
        }

        @Test
        @DisplayName("every Runner dead, or the clock running out, is the Hunters'")
        void huntersWin() {
            assertThat(HuntHistory.Winner.forReason("all-runners-dead")).isEqualTo(HuntHistory.Winner.HUNTERS);
            assertThat(HuntHistory.Winner.forReason("timeout")).isEqualTo(HuntHistory.Winner.HUNTERS);
        }

        @Test
        @DisplayName("a manual stop, a plugin disable, or nothing at all — nobody's")
        void nobodyWins() {
            assertThat(HuntHistory.Winner.forReason("manual")).isEqualTo(HuntHistory.Winner.NONE);
            assertThat(HuntHistory.Winner.forReason("plugin-disable")).isEqualTo(HuntHistory.Winner.NONE);
            assertThat(HuntHistory.Winner.forReason(null)).isEqualTo(HuntHistory.Winner.NONE);
        }
    }

    @Nested
    @DisplayName("recording a hunt")
    class Recording {

        @Test
        @DisplayName("a finished hunt is written and comes back out with everybody on the right side")
        void roundTrips() {
            Instant startedAt = Instant.parse("2026-09-01T10:00:00Z");
            SpeedrunOutcome outcome = new SpeedrunOutcome("portal-exit", Duration.ofMinutes(12),
                    startedAt.plus(Duration.ofMinutes(12)));

            boolean written = history.record(startedAt, Set.of(runnerA, runnerB), Set.of(hunterA), outcome);

            assertThat(written).isTrue();
            List<HuntHistory.Entry> recent = history.recent(10);
            assertThat(recent).hasSize(1);
            HuntHistory.Entry entry = recent.get(0);
            assertThat(entry.winner()).isEqualTo(HuntHistory.Winner.RUNNERS);
            assertThat(entry.reason()).isEqualTo("portal-exit");
            assertThat(entry.elapsed()).isEqualTo(Duration.ofMinutes(12));
            assertThat(entry.startedAt()).isEqualTo(startedAt);
            assertThat(entry.runners()).containsExactlyInAnyOrder(runnerA, runnerB);
            assertThat(entry.hunters()).containsExactly(hunterA);
        }

        @Test
        @DisplayName("no startedAt, or no outcome, is refused rather than half-written")
        void refusesIncompleteRecords() {
            SpeedrunOutcome outcome = new SpeedrunOutcome("timeout", Duration.ofMinutes(5), Instant.now());
            assertThat(history.record(null, Set.of(runnerA), Set.of(hunterA), outcome)).isFalse();
            assertThat(history.record(Instant.now(), Set.of(runnerA), Set.of(hunterA), null)).isFalse();
            assertThat(history.recent(10)).isEmpty();
        }

        @Test
        @DisplayName("a hunt nobody was on is still a hunt")
        void emptySidesStillRecord() {
            SpeedrunOutcome outcome = new SpeedrunOutcome("manual", Duration.ZERO, Instant.now());
            boolean written = history.record(Instant.now(), Set.of(), Set.of(), outcome);
            assertThat(written).isTrue();
            assertThat(history.recent(10)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("recent()")
    class Recent {

        @Test
        @DisplayName("newest first, and never more than asked for")
        void newestFirstAndBounded() {
            record(1);
            record(2);
            record(3);

            List<HuntHistory.Entry> two = history.recent(2);
            assertThat(two).hasSize(2);
            assertThat(two.get(0).reason()).isEqualTo("portal-exit-3");
            assertThat(two.get(1).reason()).isEqualTo("portal-exit-2");
        }

        @Test
        @DisplayName("a limit of zero, or less, is an empty list rather than every row")
        void nonPositiveLimitIsEmpty() {
            record(1);
            assertThat(history.recent(0)).isEmpty();
            assertThat(history.recent(-3)).isEmpty();
        }

        private void record(int n) {
            Instant startedAt = Instant.parse("2026-09-01T10:00:00Z").plusSeconds(n);
            SpeedrunOutcome outcome = new SpeedrunOutcome("portal-exit-" + n, Duration.ofMinutes(n),
                    startedAt.plus(Duration.ofMinutes(n)));
            history.record(startedAt, Set.of(runnerA), Set.of(hunterA), outcome);
        }
    }

    @Nested
    @DisplayName("forPlayer()")
    class ForPlayer {

        @Test
        @DisplayName("only hunts a player was actually in, on either side")
        void onlyTheirOwn() {
            Instant t = Instant.parse("2026-09-01T10:00:00Z");
            history.record(t, Set.of(runnerA), Set.of(hunterA),
                    new SpeedrunOutcome("portal-exit", Duration.ofMinutes(5), t.plus(Duration.ofMinutes(5))));
            history.record(t.plusSeconds(1), Set.of(runnerB), Set.of(hunterA),
                    new SpeedrunOutcome("timeout", Duration.ofMinutes(8), t.plus(Duration.ofMinutes(9))));

            assertThat(history.forPlayer(runnerA, 10)).hasSize(1);
            assertThat(history.forPlayer(hunterA, 10)).hasSize(2);
            assertThat(history.forPlayer(runnerB, 10)).hasSize(1);
        }

        @Test
        @DisplayName("nobody, or nothing recorded, is an empty list")
        void noPlayerIsEmpty() {
            assertThat(history.forPlayer(null, 10)).isEmpty();
            assertThat(history.forPlayer(UUID.randomUUID(), 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("summary()")
    class SummaryTest {

        @Test
        @DisplayName("nothing recorded is the empty summary, not a crash")
        void emptyWhenNothingRecorded() {
            assertThat(history.summary()).isEqualTo(HuntHistory.Summary.EMPTY);
        }

        @Test
        @DisplayName("counts each side's wins and the aborted ones separately, and averages the duration")
        void addsUpCorrectly() {
            Instant t = Instant.parse("2026-09-01T10:00:00Z");
            history.record(t, Set.of(runnerA), Set.of(hunterA),
                    new SpeedrunOutcome("portal-exit", Duration.ofMinutes(10), t.plus(Duration.ofMinutes(10))));
            history.record(t, Set.of(runnerA), Set.of(hunterA),
                    new SpeedrunOutcome("timeout", Duration.ofMinutes(20), t.plus(Duration.ofMinutes(20))));
            history.record(t, Set.of(runnerA), Set.of(hunterA),
                    new SpeedrunOutcome("manual", Duration.ofMinutes(3), t.plus(Duration.ofMinutes(3))));

            HuntHistory.Summary summary = history.summary();
            assertThat(summary.total()).isEqualTo(3);
            assertThat(summary.runnerWins()).isEqualTo(1);
            assertThat(summary.hunterWins()).isEqualTo(1);
            assertThat(summary.aborted()).isEqualTo(1);
            assertThat(summary.shortest()).isEqualTo(Duration.ofMinutes(3));
            assertThat(summary.longest()).isEqualTo(Duration.ofMinutes(20));
            assertThat(summary.averageElapsed()).isEqualTo(Duration.ofMinutes(11));
        }
    }

    @Nested
    @DisplayName("forPlayerSummary()")
    class ForPlayerSummary {

        @Test
        @DisplayName("counts a win only when the player's own side is the one that won")
        void onlyOwnSideCounts() {
            Instant t = Instant.parse("2026-09-01T10:00:00Z");
            // runnerA wins as a Runner …
            history.record(t, Set.of(runnerA), Set.of(hunterA),
                    new SpeedrunOutcome("portal-exit", Duration.ofMinutes(5), t.plus(Duration.ofMinutes(5))));
            // … and loses as a Runner here — the Hunters won this one.
            history.record(t, Set.of(runnerA), Set.of(hunterA),
                    new SpeedrunOutcome("all-runners-dead", Duration.ofMinutes(7), t.plus(Duration.ofMinutes(7))));

            HuntHistory.PlayerRecord record = history.forPlayerSummary(runnerA);
            assertThat(record.played()).isEqualTo(2);
            assertThat(record.won()).isEqualTo(1);

            HuntHistory.PlayerRecord hunterRecord = history.forPlayerSummary(hunterA);
            assertThat(hunterRecord.played()).isEqualTo(2);
            assertThat(hunterRecord.won()).isEqualTo(1);
        }

        @Test
        @DisplayName("nobody asked about is the empty record")
        void unknownPlayerIsEmpty() {
            assertThat(history.forPlayerSummary(null)).isEqualTo(HuntHistory.PlayerRecord.EMPTY);
            assertThat(history.forPlayerSummary(UUID.randomUUID())).isEqualTo(HuntHistory.PlayerRecord.EMPTY);
        }
    }
}
