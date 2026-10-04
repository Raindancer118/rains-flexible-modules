package de.raindancer.modules.speedrun;

import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The old {@code history.yml} moved into Core's run history — with real files in a real folder and a
 * real SQLite database: everything comes over, nothing already there is replaced, a byte-identical
 * backup is kept before the original goes, and nothing is lost while Core's history cannot be used.
 */
class SpeedrunHistoryMigrationTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** history.yml exactly as speedrun 1.28 wrote it: two runs and Manhunt's standings. */
    private static final String OLD_FILE = """
            runs:
              run-one:
                category: minecraft:end/kill_dragon|RANDOM||
                started: 1790000000000
                millis: 1260000
                outcome: advancement:minecraft:end/kill_dragon
                completed: true
                seed: 42
                players:
                  11111111-1111-1111-1111-111111111111: Alice
                timeline:
                - kind: SPLIT
                  at: 240000
                  who: 11111111-1111-1111-1111-111111111111
                  detail: enter-nether
                - kind: DEATH
                  at: 300000
                  who: 11111111-1111-1111-1111-111111111111
                  detail: Alice burned to death
                - kind: FINISH
                  at: 1260000
                  who: ''
                  detail: advancement:minecraft:end/kill_dragon
                labels: {}
                winner: ''
                results: {}
              run-two:
                category: minecraft:end/kill_dragon|RANDOM|manhunt|
                started: 1790000100000
                millis: 900000
                outcome: manhunt:caught
                completed: false
                seed: -7
                players:
                  11111111-1111-1111-1111-111111111111: Alice
                  22222222-2222-2222-2222-222222222222: Bob
                timeline:
                - kind: CAUGHT
                  at: 900000
                  who: 11111111-1111-1111-1111-111111111111
                  detail: ''
                  other: 22222222-2222-2222-2222-222222222222
                labels: {}
                winner: hunters
                results:
                  11111111-1111-1111-1111-111111111111:
                    name: Alice
                    runner: true
                    won: false
                    caught: true
                    catches: 0
                    deaths: 0
                    survived-millis: 900000
                    distance: 1200.5
                    portals: 1
                  22222222-2222-2222-2222-222222222222:
                    name: Bob
                    runner: false
                    won: true
                    caught: false
                    catches: 1
                    deaths: 0
                    survived-millis: 0
                    distance: 900.0
                    portals: 0
            standings:
              manhunt:
                11111111-1111-1111-1111-111111111111:
                  name: Alice
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
            """;

    @TempDir
    Path folder;

    private Database database;

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private SpeedrunHistory history() {
        RunHistory runs = new RunHistory(database, SpeedrunHistory.GAME);
        runs.load();
        return new SpeedrunHistory(runs, new YamlStore(folder.resolve("standings.yml")), Runnable::run);
    }

    private Path old() throws Exception {
        Path file = folder.resolve("history.yml");
        Files.writeString(file, OLD_FILE);
        return file;
    }

    @Test
    @DisplayName("every run moves over as it was, the standings with it, and the history keeps working across a restart")
    void movesEverything() throws Exception {
        old();
        SpeedrunHistory history = history();

        assertThat(SpeedrunHistoryMigration.migrate(folder, history)).isEqualTo(SpeedrunHistoryMigration.Result.MOVED);

        SpeedrunHistory restarted = history();
        assertThat(restarted.all()).extracting(SpeedrunRunRecord::id).containsExactly("run-one", "run-two");
        SpeedrunRunRecord first = restarted.byId("run-one").orElseThrow();
        assertThat(first.time()).isEqualTo(Duration.ofMinutes(21));
        assertThat(first.seed()).isEqualTo(42L);
        assertThat(first.deaths()).isEqualTo(1);
        assertThat(first.splitAt("enter-nether")).contains(Duration.ofMinutes(4));
        SpeedrunRunRecord hunt = restarted.byId("run-two").orElseThrow();
        assertThat(hunt.winner()).isEqualTo(SpeedrunHistory.HUNTERS);
        assertThat(hunt.resultOf(BOB).orElseThrow().catches()).isEqualTo(1);
        assertThat(hunt.timeline().getFirst().other()).isEqualTo(BOB);
        assertThat(restarted.record(new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.RANDOM, "", "")))
                .contains(first);
        assertThat(restarted.standing("manhunt", ALICE).orElseThrow().rating()).isEqualTo(1043.5);
        assertThat(restarted.numberOf(hunt)).as("numbered as before").isEqualTo(2);
    }

    @Test
    @DisplayName("a byte-identical backup is kept before the original goes")
    void backupFirst() throws Exception {
        old();

        SpeedrunHistoryMigration.migrate(folder, history());

        assertThat(folder.resolve("history.yml")).doesNotExist();
        assertThat(Files.readString(folder.resolve("backup/history.yml"))).isEqualTo(OLD_FILE);
    }

    @Test
    @DisplayName("a run Core's history already holds is never replaced, and an existing backup never overwritten")
    void neverOverwrites() throws Exception {
        old();
        SpeedrunHistory history = history();
        SpeedrunRunRecord newer = new SpeedrunRunRecord("run-one",
                new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.RANDOM, "", ""), 1790000000000L,
                Duration.ofMinutes(19), "advancement:x", true, 42L, java.util.Map.of(ALICE, "Alice"),
                java.util.List.of(), java.util.Map.of());
        history.add(newer);
        Files.createDirectories(folder.resolve("backup"));
        Files.writeString(folder.resolve("backup/history.yml"), "an older backup");

        SpeedrunHistoryMigration.migrate(folder, history);

        assertThat(history.byId("run-one").orElseThrow().time()).isEqualTo(Duration.ofMinutes(19));
        assertThat(Files.readString(folder.resolve("backup/history.yml"))).isEqualTo("an older backup");
        try (var kept = Files.list(folder.resolve("backup"))) {
            assertThat(kept.filter(path -> !path.getFileName().toString().equals("history.yml")))
                    .singleElement().satisfies(path -> assertThat(Files.readString(path)).isEqualTo(OLD_FILE));
        }
    }

    @Test
    @DisplayName("existing standings are kept; only somebody without one gets the old one")
    void standingsNeverOverwritten() throws Exception {
        old();
        Files.writeString(folder.resolve("standings.yml"), """
                standings:
                  manhunt:
                    11111111-1111-1111-1111-111111111111:
                      name: Alice
                      rating: 1100.0
                """);

        SpeedrunHistory history = history();
        SpeedrunHistoryMigration.migrate(folder, history);

        assertThat(history().standing("manhunt", ALICE).orElseThrow().rating()).isEqualTo(1100.0);
    }

    @Test
    @DisplayName("while Core's history is not loaded yet, the old runs are shown and the file stays where it is")
    void waitsForCore() throws Exception {
        Path file = old();
        RunHistory notLoaded = new RunHistory(database, SpeedrunHistory.GAME);
        SpeedrunHistory history = new SpeedrunHistory(notLoaded, new YamlStore(folder.resolve("standings.yml")),
                Runnable::run);

        SpeedrunHistoryMigration.show(folder, history);
        SpeedrunHistoryMigration.Result result = SpeedrunHistoryMigration.migrate(folder, history);

        assertThat(result).isEqualTo(SpeedrunHistoryMigration.Result.WAITING);
        assertThat(history.all()).extracting(SpeedrunRunRecord::id).containsExactly("run-one", "run-two");
        assertThat(Files.readString(file)).isEqualTo(OLD_FILE);
        assertThat(folder.resolve("backup")).doesNotExist();
    }

    @Test
    @DisplayName("Core's database unusable: nothing is moved, nothing deleted")
    void databaseBroken() throws Exception {
        Path file = old();
        Path broken = folder.resolve("not-a-database");
        Files.createDirectories(broken);
        try (Database unusable = Database.open(broken, CoreSchema.CORE, () -> false)) {
            RunHistory runs = new RunHistory(unusable, SpeedrunHistory.GAME);
            runs.load();
            SpeedrunHistory history = new SpeedrunHistory(runs, new YamlStore(folder.resolve("standings.yml")),
                    Runnable::run);

            assertThat(SpeedrunHistoryMigration.migrate(folder, history))
                    .isEqualTo(SpeedrunHistoryMigration.Result.WAITING);
        }
        assertThat(Files.readString(file)).isEqualTo(OLD_FILE);
    }

    @Test
    @DisplayName("an old file that cannot be read is left exactly as it is")
    void unreadableLeftAlone() throws Exception {
        Path file = folder.resolve("history.yml");
        Files.writeString(file, "runs: [this is: not: yaml");

        assertThat(SpeedrunHistoryMigration.migrate(folder, history()))
                .isEqualTo(SpeedrunHistoryMigration.Result.LEFT_ALONE);

        assertThat(Files.readString(file)).isEqualTo("runs: [this is: not: yaml");
    }

    @Test
    @DisplayName("nothing to move: nothing happens; moved once: never again")
    void once() throws Exception {
        assertThat(SpeedrunHistoryMigration.migrate(folder, history())).isEqualTo(SpeedrunHistoryMigration.Result.NOTHING);
        old();
        SpeedrunHistoryMigration.migrate(folder, history());

        assertThat(SpeedrunHistoryMigration.migrate(folder, history())).isEqualTo(SpeedrunHistoryMigration.Result.NOTHING);
        assertThat(history().all()).hasSize(2);
    }
}
