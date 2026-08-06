package de.raindancer.modules.hungergames;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.MannequinSimService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mannequin simulation, run entirely through real {@link GameSession} whitelisting, team assignment
 * and elimination — see the class note on why that is the point of the whole feature.
 */
@ExtendWith(MockitoExtension.class)
class MannequinSimServiceTest {

    /** A fake world: every spawn call gets a fresh entity id, and death locations are remembered. */
    private static final class FakeMannequins implements MannequinSimService.Mannequins {
        final Map<UUID, Location> locations = new HashMap<>();
        final List<UUID> removed = new ArrayList<>();
        final List<TeamColour> spawnedColours = new ArrayList<>();
        int eliminatedWithKiller;
        int eliminatedWithoutKiller;

        @Override
        public void moveTo(UUID entityId, Location where) {
            // Remembered, not ignored: placeOnPlatforms is what makes a rehearsal look like the round it
            // rehearses, and a fake that dropped the move would let a broken one pass.
            locations.put(entityId, where);
        }

        @Override
        public UUID spawn(Location base, String displayName, TeamColour colour) {
            UUID id = UUID.randomUUID();
            locations.put(id, base);
            spawnedColours.add(colour);
            return id;
        }

        @Override
        public void remove(UUID entityId) {
            removed.add(entityId);
            locations.remove(entityId);
        }

        @Override
        public Optional<Location> locationOf(UUID entityId) {
            return Optional.ofNullable(locations.get(entityId));
        }

        @Override
        public void markEliminated(Location location, boolean hadKiller) {
            if (hadKiller) {
                eliminatedWithKiller++;
            } else {
                eliminatedWithoutKiller++;
            }
        }
    }

    private GameSession session;
    private FakeMannequins mannequins;
    private final List<String> logs = new ArrayList<>();
    private MannequinSimService service;
    private Player admin;
    private Location adminLocation;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        mannequins = new FakeMannequins();
        service = new MannequinSimService(session, mannequins,
                (category, message, location) -> logs.add(category + ":" + message));
        admin = mock(Player.class);
        adminLocation = new Location(null, 0, 64, 0);
        // Only reached once spawn() actually gets to spawning something; the tests that check spawn()'s
        // preconditions refuse before touching admin at all, so these two are lenient rather than required.
        lenient().when(admin.getLocation()).thenReturn(adminLocation);
        lenient().when(admin.getName()).thenReturn("Admin");
    }

    private void toStartup() {
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
    }

    @Test
    @DisplayName("spawning is possible before teams freeze")
    void canSpawnBeforeTeamsFreeze() {
        assertThat(service.canSpawn()).isTrue();
    }

    @Test
    @DisplayName("spawning is refused once teams are frozen")
    void cannotSpawnOnceFrozen() {
        toStartup(); // TeamRules.defaults() locks teams from STARTUP onward

        assertThat(service.canSpawn()).isFalse();
        assertThat(service.spawn(admin, 3)).contains("start-up sequence");
        assertThat(service.mannequinCount()).isZero();
    }

    @Test
    @DisplayName("spawning three mannequins registers three tributes on real teams")
    void spawningRegistersRealTributes() {
        String result = service.spawn(admin, 3);

        assertThat(result).contains("3 mannequin tribute(s)");
        assertThat(service.mannequinCount()).isEqualTo(3);
        assertThat(service.aliveCount()).isEqualTo(3);
        assertThat(session.participants().all()).hasSize(3);
        assertThat(logs).anyMatch(line -> line.contains("Admin"));
    }

    @Test
    @DisplayName("count must be positive")
    void countMustBePositive() {
        assertThat(service.spawn(admin, 0)).contains("positive");
        assertThat(service.spawn(admin, -1)).contains("positive");
    }

    @Test
    @DisplayName("a fresh test team opens once the one being filled reports TEAM_FULL")
    void opensANewTeamWhenTheCurrentOneIsFull() {
        // TeamRules.defaults() caps a team at two members, so the third mannequin must land on a second
        // team without this test needing to know that cap's value directly.
        service.spawn(admin, 3);

        assertThat(service.teamCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("eliminateOne runs the real elimination flow and removes the entity")
    void eliminateOneRunsTheRealFlow() {
        toRunningWithMannequins(2);

        Optional<String> result = service.eliminateOne(admin);

        assertThat(result).isEmpty();
        assertThat(service.aliveCount()).isEqualTo(1);
        assertThat(mannequins.removed).hasSize(1);
    }

    @Test
    @DisplayName("eliminateOne refuses outside RUNNING")
    void eliminateOneRefusesOutsideRunning() {
        service.spawn(admin, 1);

        assertThat(service.eliminateOne(admin)).isPresent();
    }

    @Test
    @DisplayName("eliminateOne says so once every mannequin is already gone")
    void eliminateOneWhenNoneLeft() {
        // Two real tributes are whitelisted alongside the mannequin so eliminating it does not, by itself,
        // leave a single survivor and end the round — WinnerRule would otherwise declare a winner and move
        // the session to FINISHED, which is a different refusal from the one this test is about.
        session.whitelistAdd(UUID.randomUUID(), "Gale");
        session.whitelistAdd(UUID.randomUUID(), "Rue");
        toRunningWithMannequins(1);
        service.eliminateOne(admin);

        assertThat(service.eliminateOne(admin)).contains("no living mannequin left");
    }

    @Test
    @DisplayName("clear removes every mannequin, its participant, and every test team")
    void clearRemovesEverything() {
        service.spawn(admin, 3);

        int removed = service.clear();

        assertThat(removed).isEqualTo(3);
        assertThat(service.mannequinCount()).isZero();
        assertThat(service.teamCount()).isZero();
        assertThat(session.participants().all()).isEmpty();
    }

    @Test
    @DisplayName("clear leaves the counters ready for a fresh simulation")
    void clearThenSpawnAgainStartsClean() {
        service.spawn(admin, 3);
        service.clear();

        service.spawn(admin, 1);

        assertThat(service.mannequinCount()).isEqualTo(1);
        assertThat(session.participants().all()).hasSize(1);
    }

    private void toRunningWithMannequins(int count) {
        service.spawn(admin, count);
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
    }
}
