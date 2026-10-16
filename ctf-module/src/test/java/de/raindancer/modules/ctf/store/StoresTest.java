package de.raindancer.modules.ctf.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.ctf.model.CaptureShow;
import de.raindancer.modules.ctf.model.Challenge;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Stats;
import de.raindancer.modules.ctf.model.Trail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
@DisplayName("records, profiles, challenges and the lobby on disk")
class StoresTest {

    @TempDir
    Path folder;

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Test
    @DisplayName("rounds add up; the fastest capture is the smallest one ever, never a zero")
    void stats() {
        Database database = TestDatabases.open(folder.resolve("ctf.db"), CtfDatabase.SCHEMA);
        StatsStore stats = new StatsStore(database);
        stats.add(new StatsStore.Delta(ALICE, "Alice", true, false, 2, 1, 5, 3, 1, true, 20_000, 170), 100);
        stats.add(new StatsStore.Delta(ALICE, "Alice", false, true, 1, 0, 2, 4, 0, false, 12_000, 60), 100);
        stats.add(new StatsStore.Delta(ALICE, "Alice", false, true, 0, 0, 0, 1, 0, false, 0, 20), 101);
        stats.add(new StatsStore.Delta(BOB, "Bob", true, false, 0, 3, 1, 1, 0, false, 0, 90), 100);

        Stats alice = stats.of(ALICE).orElseThrow();
        assertThat(alice.rounds()).isEqualTo(3);
        assertThat(alice.wins()).isEqualTo(1);
        assertThat(alice.losses()).isEqualTo(2);
        assertThat(alice.captures()).isEqualTo(3);
        assertThat(alice.mvps()).isEqualTo(1);
        assertThat(alice.fastestCapture()).isEqualTo(12_000);
        assertThat(alice.xp()).isEqualTo(250);

        assertThat(stats.top(StatsStore.Board.CAPTURES, 5)).extracting(Stats::name).containsExactly("Alice");
        assertThat(stats.top(StatsStore.Board.WINS, 5)).extracting(Stats::name).containsExactly("Alice", "Bob");
        assertThat(stats.week(100, 5)).extracting(StatsStore.Weekly::name).containsExactly("Alice", "Bob");
        assertThat(stats.week(101, 5)).as("no captures and no wins that week").isEmpty();

        stats.addXp(BOB, "Bob", 150);
        assertThat(stats.of(BOB).orElseThrow().xp()).isEqualTo(240);
    }

    @Test
    @DisplayName("a profile and a day's challenges come back as saved")
    void profile() {
        Database database = TestDatabases.open(folder.resolve("ctf.db"), CtfDatabase.SCHEMA);
        ProfileStore profiles = new ProfileStore(database);
        assertThat(profiles.of(ALICE)).isEqualTo(ProfileStore.Profile.defaults());
        profiles.save(ALICE, new ProfileStore.Profile(Trail.HEARTS, CaptureShow.STAR, Role.MEDIC));
        assertThat(profiles.of(ALICE)).isEqualTo(new ProfileStore.Profile(Trail.HEARTS, CaptureShow.STAR, Role.MEDIC));

        profiles.setChallenge(ALICE, 20_000, Challenge.CAPTURE, 2);
        profiles.setChallenge(ALICE, 20_000, Challenge.CAPTURE, 3);
        profiles.setChallenge(ALICE, 20_000, Challenge.KILL, 7);
        assertThat(profiles.challenges(ALICE, 20_000)).containsEntry(Challenge.CAPTURE, 3).containsEntry(Challenge.KILL, 7);
        assertThat(profiles.challenges(ALICE, 20_001)).isEmpty();
    }

    @Test
    @DisplayName("the lobby's places come back as saved")
    void lobby() {
        LobbyStore store = new LobbyStore(folder.resolve("lobby.yml"));
        LobbyStore.Lobby lobby = LobbyStore.Lobby.empty()
                .withSpawn(new LobbyStore.Place("world", new Point(0.5, 70, 0.5, 180f, 0f)))
                .withParkourStart(new LobbyStore.Place("world", Point.of(10, 70, 10)))
                .withCheckpoint(new LobbyStore.Place("world", Point.of(20, 75, 10)))
                .withParkourFinish(new LobbyStore.Place("world", Point.of(30, 80, 10)))
                .withThrowLine(new LobbyStore.Place("world", Point.of(-10, 70, 0)));
        store.save(lobby);
        assertThat(new LobbyStore(folder.resolve("lobby.yml")).load()).isEqualTo(lobby);
        assertThat(lobby.hasParkour()).isTrue();
    }
}
