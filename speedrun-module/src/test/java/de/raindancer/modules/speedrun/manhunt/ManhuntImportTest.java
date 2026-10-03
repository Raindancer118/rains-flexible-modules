package de.raindancer.modules.speedrun.manhunt;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunRunRecord;
import de.raindancer.modules.speedrun.SpeedrunTimeline;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manhunt's old {@code stats.yml} and {@code hunts.yml}, as the separate plugin wrote them, taken into
 * the one speedrun history — ratings exactly as they were, hunts with their whole timeline.
 */
class ManhuntImportTest {

    private static final UUID ANNA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BEN = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path folder;

    private SpeedrunHistory history() {
        SpeedrunHistory history = new SpeedrunHistory(new YamlStore(folder.resolve("history.yml")), Runnable::run);
        history.load();
        return history;
    }

    /** Exactly the shape StatsStore and HistoryStore wrote. */
    private void oldFiles() throws IOException {
        Files.writeString(folder.resolve("stats.yml"), """
                players:
                  11111111-1111-1111-1111-111111111111:
                    name: Anna
                    rating: 1043.5
                    hunts: 3
                    runner-hunts: 2
                    runner-wins: 1
                    hunter-hunts: 1
                    hunter-wins: 1
                    catches: 2
                    deaths: 1
                    times-caught: 1
                    survived-millis: 900000
                    best-survival-millis: 600000
                    distance: 1234.5
                    portals: 4
                """);
        Files.writeString(folder.resolve("hunts.yml"), """
                next-number: 8
                hunts:
                  7:
                    number: 7
                    started-at: 1790000000000
                    duration-millis: 1260000
                    reason: manhunt:caught
                    winner: HUNTERS
                    players:
                      11111111-1111-1111-1111-111111111111:
                        name: Anna
                        runner: true
                        won: false
                        caught: true
                        catches: 0
                        deaths: 1
                        survived-millis: 1200000
                        distance: 800.0
                        portals: 2
                      22222222-2222-2222-2222-222222222222:
                        name: Ben
                        runner: false
                        won: true
                        caught: false
                        catches: 1
                        deaths: 0
                        survived-millis: 0
                        distance: 900.0
                        portals: 1
                    events:
                      0:
                        at: 0
                        kind: STARTED
                        detail: '1'
                      1:
                        at: 300000
                        kind: MILESTONE
                        who: 11111111-1111-1111-1111-111111111111
                        who-name: Anna
                        detail: nether
                      2:
                        at: 1200000
                        kind: CAUGHT
                        who: 11111111-1111-1111-1111-111111111111
                        who-name: Anna
                        other: 22222222-2222-2222-2222-222222222222
                        other-name: Ben
                      3:
                        at: 1260000
                        kind: FINISHED
                        detail: manhunt:caught
                """);
    }

    @Test
    @DisplayName("ratings and stats come over exactly as they were — nobody's rating moves by the move")
    void standings() throws IOException {
        oldFiles();
        SpeedrunHistory history = history();

        ManhuntImport.run(folder, history);

        var anna = history.standing(ManhuntMode.ID, ANNA).orElseThrow();
        assertThat(anna.rating()).isEqualTo(1043.5);
        assertThat(anna.runnerWins()).isEqualTo(1);
        assertThat(anna.catches()).isEqualTo(2);
        assertThat(anna.bestSurvivalMillis()).isEqualTo(600000);
    }

    @Test
    @DisplayName("each past hunt becomes a run of the history: winner, players, catches and splits")
    void hunts() throws IOException {
        oldFiles();
        SpeedrunHistory history = history();

        ManhuntImport.run(folder, history);

        SpeedrunRunRecord hunt = history.all().getFirst();
        assertThat(hunt.category().mode()).isEqualTo(ManhuntMode.ID);
        assertThat(hunt.winner()).isEqualTo(SpeedrunHistory.HUNTERS);
        assertThat(hunt.time()).isEqualTo(Duration.ofMinutes(21));
        assertThat(hunt.participants()).containsEntry(ANNA, "Anna").containsEntry(BEN, "Ben");
        assertThat(hunt.resultOf(BEN).orElseThrow().catches()).isEqualTo(1);
        assertThat(hunt.splitAt("enter-nether")).contains(Duration.ofMinutes(5));
        assertThat(hunt.timeline()).anySatisfy(entry -> {
            assertThat(entry.kind()).isEqualTo(SpeedrunTimeline.Kind.CAUGHT);
            assertThat(entry.who()).isEqualTo(ANNA);
            assertThat(entry.other()).isEqualTo(BEN);
        });
    }

    @Test
    @DisplayName("the import survives a restart, and a second start imports nothing twice")
    void once() throws IOException {
        oldFiles();
        ManhuntImport.run(folder, history());

        SpeedrunHistory reread = history();
        ManhuntImport.run(folder, reread);

        assertThat(reread.all()).hasSize(1);
        assertThat(reread.rating(ManhuntMode.ID, ANNA)).isEqualTo(1043.5);
        assertThat(Files.exists(folder.resolve("stats.yml"))).isFalse();
        assertThat(Files.exists(folder.resolve("stats.yml.imported"))).isTrue();
        assertThat(Files.exists(folder.resolve("hunts.yml.imported"))).isTrue();
    }

    @Test
    @DisplayName("an old hunt does not move a rating again: the old stats already counted it")
    void noDoubleRating() throws IOException {
        oldFiles();
        SpeedrunHistory history = history();

        ManhuntImport.run(folder, history);

        assertThat(history.standing(ManhuntMode.ID, BEN)).isEmpty();
    }

    @Test
    @DisplayName("nothing to import leaves the history as it is")
    void nothing() {
        SpeedrunHistory history = history();

        ManhuntImport.run(folder, history);

        assertThat(history.all()).isEmpty();
    }
}
