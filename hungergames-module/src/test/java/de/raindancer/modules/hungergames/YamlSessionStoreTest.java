package de.raindancer.modules.hungergames;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.ParticipantState;
import de.raindancer.modules.hungergames.model.SessionSnapshot;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.store.YamlSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link YamlSessionStore}: the round-trip a restart depends on, and what happens when it cannot trust the file. */
class YamlSessionStoreTest {

    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();

    private SessionSnapshot fullSnapshot() {
        Team team = Team.of(new TeamId("careers"), "Careers", TeamColour.RED)
                .withMembers(Set.of(p1, p2))
                .withCaptain(Optional.of(p1));
        return new SessionSnapshot(
                GamePhase.RUNNING,
                List.of(new SessionSnapshot.ParticipantData(p1, "Anna", ParticipantState.ALIVE),
                        new SessionSnapshot.ParticipantData(p2, "Bela", ParticipantState.ELIMINATED)),
                List.of(team),
                new Winner.Solo(p1),
                Map.of(p1, 3),
                123_456L);
    }

    @Test
    @DisplayName("a full round survives a save and a load, exactly")
    void roundTrip(@TempDir Path dir) {
        YamlSessionStore store = new YamlSessionStore(dir.resolve("session.yml"));
        SessionSnapshot original = fullSnapshot();

        store.save(original);
        SessionSnapshot restored = store.load().orElseThrow();

        assertThat(restored).isEqualTo(original);
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("no file yet is an empty result, not an exception")
    void missingFile(@TempDir Path dir) {
        YamlSessionStore store = new YamlSessionStore(dir.resolve("session.yml"));

        assertThat(store.load()).isEmpty();
        assertThat(store.problems()).isEmpty();
    }

    @Test
    @DisplayName("a corrupt file is reported and quarantined, never silently trusted or overwritten in place")
    void corruptFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.yml");
        Files.writeString(file, "phase: [this is not valid yaml: [[[");
        YamlSessionStore store = new YamlSessionStore(file);

        Optional<SessionSnapshot> result = store.load();

        assertThat(result).isEmpty();
        assertThat(store.problems()).isNotEmpty();
        // the broken file is kept under a different name rather than deleted or left to be written over
        try (Stream<Path> siblings = Files.list(dir)) {
            assertThat(siblings).anyMatch(p -> p.getFileName().toString().startsWith("session.yml.broken-"));
        }
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("clear removes the file so the next round starts from nothing")
    void clearRemovesTheFile(@TempDir Path dir) {
        YamlSessionStore store = new YamlSessionStore(dir.resolve("session.yml"));
        store.save(fullSnapshot());

        store.clear();

        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("an unresolvable participant entry is skipped, not fatal to the rest of the round")
    void skipsOneBrokenParticipant(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.yml");
        Files.writeString(file, """
                phase: RUNNING
                participants:
                  - uuid: "not-a-uuid"
                    name: "Ghost"
                    state: ALIVE
                  - uuid: "%s"
                    name: "Anna"
                    state: ALIVE
                """.formatted(p1));
        YamlSessionStore store = new YamlSessionStore(file);

        SessionSnapshot snapshot = store.load().orElseThrow();

        assertThat(snapshot.participants()).hasSize(1);
        assertThat(snapshot.participants().get(0).uuid()).isEqualTo(p1);
        assertThat(store.problems()).isNotEmpty();
    }
}
