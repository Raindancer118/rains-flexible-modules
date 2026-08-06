package de.raindancer.modules.hungergames;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.store.GameEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link RoundLogService}: the round's own written record.
 *
 * <p>Drives it exactly the way {@code store.GameSession} would — through the {@link GameEvents} methods —
 * since that is the entire point of the class implementing that interface directly.
 */
class RoundLogServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private final List<String> loggedWarnings = new ArrayList<>();
    private LocalDateTime now = LocalDateTime.of(2026, 1, 1, 20, 0, 0);

    private RoundLogService service;
    private Path logsDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        logsDir = tempDir.resolve("logs");
        service = newService(logsDir);
        service.settings(HungerGamesSettings.DEFAULTS);
    }

    private RoundLogService newService(Path dir) {
        return new RoundLogService(dir, this::nameOf, this::teamNameOf, recordingLog(), () -> now);
    }

    private String nameOf(UUID uuid) {
        if (uuid.equals(ALICE)) {
            return "Alice";
        }
        if (uuid.equals(BOB)) {
            return "Bob";
        }
        return uuid.toString();
    }

    private String teamNameOf(TeamId id) {
        return "Team-" + id.value();
    }

    /** As {@code HttpApiServiceTest}'s own helper: a mocked {@link LogChannel} that records every warn(). */
    private LogChannel recordingLog() {
        return mock(LogChannel.class, call -> {
            if (call.getArguments().length > 0 && call.getArgument(0) instanceof String line) {
                loggedWarnings.add(line);
            }
            return null;
        });
    }

    private String readLog() throws IOException {
        return Files.readString(service.currentFile());
    }

    @Test
    @DisplayName("a kill is written with both names and the running total")
    void killIsLogged() throws IOException {
        service.kill(ALICE, BOB, 3);

        assertThat(readLog()).contains("[KILL]").contains("Alice").contains("Bob").contains("total kills: 3");
    }

    @Test
    @DisplayName("an elimination names the killer when there is one")
    void eliminationWithKiller() throws IOException {
        service.participantEliminated(BOB, ALICE, 5);

        assertThat(readLog()).contains("[ELIMINATION]").contains("Bob").contains("by Alice")
                .contains("5 tribute(s) remain");
    }

    @Test
    @DisplayName("an environmental elimination names no killer")
    void eliminationWithoutKiller() throws IOException {
        service.participantEliminated(BOB, null, 1);

        assertThat(readLog()).contains("Bob").doesNotContain(" by ");
    }

    @Test
    @DisplayName("a revive is written")
    void reviveIsLogged() throws IOException {
        service.participantRevived(ALICE);

        assertThat(readLog()).contains("[REVIVE]").contains("Alice");
    }

    @Test
    @DisplayName("a solo winner is described by name")
    void soloWinnerIsLogged() throws IOException {
        service.winnerDeclared(new Winner.Solo(ALICE));

        assertThat(readLog()).contains("[WINNER]").contains("solo winner: Alice");
    }

    @Test
    @DisplayName("a team winner is described by team name and members")
    void teamWinnerIsLogged() throws IOException {
        TeamId team = new TeamId("red");
        service.winnerDeclared(new Winner.Team(team, Set.of(ALICE, BOB)));

        String log = readLog();
        assertThat(log).contains("team winner: Team-red").contains("Alice").contains("Bob");
    }

    @Test
    @DisplayName("no winner is still a written result, not a blank line")
    void noWinnerIsLogged() throws IOException {
        service.winnerDeclared(new Winner.None());

        assertThat(readLog()).contains("no winner");
    }

    @Test
    @DisplayName("a phase change is written with both phases")
    void phaseChangeIsLogged() throws IOException {
        service.phaseChanged(GamePhase.LOBBY, GamePhase.STARTUP);

        assertThat(readLog()).contains("[PHASE]").contains("LOBBY -> STARTUP");
    }

    @Test
    @DisplayName("moving into PREFLIGHT starts a fresh file, per round")
    void preflightStartsAFreshFile() throws IOException {
        service.kill(ALICE, BOB, 1);
        Path firstFile = service.currentFile();

        now = now.plusHours(1);
        service.phaseChanged(GamePhase.FINISHED, GamePhase.PREFLIGHT);
        service.kill(BOB, ALICE, 1);
        Path secondFile = service.currentFile();

        assertThat(secondFile).isNotEqualTo(firstFile);
        assertThat(Files.exists(firstFile)).isTrue();
        assertThat(Files.exists(secondFile)).isTrue();
    }

    @Test
    @DisplayName("switching off the round log writes nothing at all")
    void disabledWritesNothing() {
        service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "roundLogEnabled", false));

        service.kill(ALICE, BOB, 1);

        assertThat(Files.exists(logsDir)).isFalse();
    }

    @Test
    @DisplayName("one file for the whole session when file-per-round is off")
    void oneFileWhenNotPerRound() throws IOException {
        service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "roundLogFilePerRound", false));

        service.kill(ALICE, BOB, 1);
        service.phaseChanged(GamePhase.FINISHED, GamePhase.PREFLIGHT);
        service.kill(BOB, ALICE, 1);

        assertThat(service.currentFile().getFileName().toString()).isEqualTo("rounds.log");
        assertThat(readLog()).contains("[KILL]");
    }

    @Test
    @DisplayName("coordinates are appended only when the setting says so")
    void coordinatesAreOptional() throws IOException {
        RoundLogService.Coordinates where = new RoundLogService.Coordinates("world", 10, 64, -5);

        service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "roundLogIncludeCoordinates", true));
        service.log("EVENT", "something happened", where);
        assertThat(readLog()).contains("world 10/64/-5");

        RoundLogService withoutCoords = newService(logsDir.resolve("other"));
        withoutCoords.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "roundLogIncludeCoordinates", false));
        withoutCoords.log("EVENT", "something happened", where);
        assertThat(Files.readString(withoutCoords.currentFile())).doesNotContain("world 10/64/-5");
    }

    @Test
    @DisplayName("a write failure is reported to the log channel, not thrown")
    void unwritableDirectoryDoesNotThrow() throws IOException {
        // A file where the log directory should be: Files.createDirectories fails, and the class must
        // survive that rather than propagate an IOException into GameSession's mutation path.
        Path blocked = logsDir.getParent().resolve("blocked");
        Files.writeString(blocked, "not a directory");
        RoundLogService onABlockedDir = newService(blocked.resolve("logs"));
        onABlockedDir.settings(HungerGamesSettings.DEFAULTS);

        onABlockedDir.kill(ALICE, BOB, 1);

        assertThat(loggedWarnings).isNotEmpty();
    }

    @Test
    @DisplayName("whitelist and team-membership changes are written")
    void otherEventsAreLogged() throws IOException {
        service.whitelistChanged(ALICE, true);
        assertThat(readLog()).contains("[WHITELIST]").contains("Alice added");

        service.teamMembershipChanged(BOB, null, new TeamId("blue"), GameEvents.MembershipCause.RANDOM);
        assertThat(readLog()).contains("[TEAM]").contains("Bob").contains("Team-blue").contains("RANDOM");
    }
}
