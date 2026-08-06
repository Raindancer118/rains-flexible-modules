package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.LobbyBoxService;
import de.raindancer.modules.hungergames.service.LobbyBoxService.Box;
import de.raindancer.modules.hungergames.service.LobbyBoxService.Point;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link LobbyBoxService}: whether the glass lobby exists, who is inside it, and who a hit must spare. */
class LobbyBoxServiceTest {

    private static final Point ARENA_CENTRE = new Point("world", 0, 100, 0);
    private static final Point LOBBY_CENTRE = new Point("world", 0, 130, 0);

    private GameSession session;
    private Optional<Box> geometry = Optional.empty();
    private LobbyBoxService lobby;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        lobby = new LobbyBoxService(session, () -> geometry);
        lobby.settings(HungerGamesSettings.DEFAULTS);
    }

    private void placeArena() {
        geometry = Optional.of(new Box(ARENA_CENTRE, LOBBY_CENTRE));
    }

    /**
     * A point genuinely inside the box's footprint.
     *
     * <p>Originally this fixture used {@code ARENA_CENTRE.y() + 20} for "inside", which is wrong: the box's
     * floor is at {@code arenaCentre.y() + settings.lobbyHeightOffset()}, and {@code DEFAULTS.lobbyHeightOffset()}
     * is 100, not 20 — the lobby floats well above the arena precisely so tributes waiting in it cannot see
     * it being built underneath them. A "+20" point sat twenty blocks above the arena and eighty blocks
     * below the box's actual floor, so {@code isInside} correctly reported it as outside, and every
     * assertion built on top of that fixture — "this is inside, so relocation/combat-blocking should not
     * happen/should happen" — was asserting the wrong outcome for a fixture that was never inside at all.
     */
    private static Point insidePoint() {
        return new Point("world", ARENA_CENTRE.x(),
                ARENA_CENTRE.y() + HungerGamesSettings.DEFAULTS.lobbyHeightOffset() + 1, ARENA_CENTRE.z());
    }

    @Test
    @DisplayName("inactive before the arena is placed, whatever the phase")
    void inactiveBeforeInit() {
        assertThat(lobby.isActive()).isFalse();
    }

    @Test
    @DisplayName("active through PREFLIGHT, LOBBY and STARTUP once the arena is placed")
    void activeThroughTheRightPhases() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        assertThat(lobby.isActive()).isTrue();

        session.transitionTo(GamePhase.LOBBY);
        assertThat(lobby.isActive()).isTrue();

        session.transitionTo(GamePhase.STARTUP);
        assertThat(lobby.isActive()).isTrue();
    }

    @Test
    @DisplayName("not active once tributes are READY or the round is RUNNING")
    void inactiveOnceReady() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);

        assertThat(lobby.isActive()).isFalse();
    }

    @Test
    @DisplayName("a point at the arena's centre, offset into the lobby, is inside the box")
    void centreOfTheBoxIsInside() {
        placeArena();

        assertThat(lobby.isInside(insidePoint())).isTrue();
    }

    @Test
    @DisplayName("far outside the box's footprint is not inside it")
    void farAwayIsOutside() {
        placeArena();
        Point farAway = new Point("world", 5_000, ARENA_CENTRE.y() + 20, 5_000);

        assertThat(lobby.isInside(farAway)).isFalse();
    }

    @Test
    @DisplayName("below the box's floor, even at the right x/z, is not inside it")
    void belowTheFloorIsOutside() {
        placeArena();
        Point onTheGround = new Point("world", ARENA_CENTRE.x(), ARENA_CENTRE.y(), ARENA_CENTRE.z());

        assertThat(lobby.isInside(onTheGround)).isFalse();
    }

    @Test
    @DisplayName("a different world is never inside, even at identical coordinates")
    void differentWorldIsOutside() {
        placeArena();
        Point sameCoordsOtherWorld = new Point("nether", ARENA_CENTRE.x(),
                ARENA_CENTRE.y() + 20, ARENA_CENTRE.z());

        assertThat(lobby.isInside(sameCoordsOtherWorld)).isFalse();
    }

    @Test
    @DisplayName("nothing is inside anything before the arena is placed")
    void nothingIsInsideBeforeInit() {
        Point anywhere = new Point("world", 0, 130, 0);

        assertThat(lobby.isInside(anywhere)).isFalse();
    }

    @Test
    @DisplayName("a whitelisted tribute standing outside the box should be relocated on join")
    void relocatesWhitelistedTributesOutside() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        UUID alice = UUID.randomUUID();
        session.whitelistAdd(alice, "Alice");
        Point outside = new Point("world", 5_000, 5_000, 5_000);

        assertThat(lobby.shouldRelocateOnJoin(alice, outside)).isTrue();
    }

    @Test
    @DisplayName("a tribute already inside the box is not relocated again")
    void doesNotRelocateSomebodyAlreadyInside() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        UUID alice = UUID.randomUUID();
        session.whitelistAdd(alice, "Alice");

        assertThat(lobby.shouldRelocateOnJoin(alice, insidePoint())).isFalse();
    }

    @Test
    @DisplayName("nobody is relocated during STARTUP — tributes are already underground for the launch")
    void noRelocationDuringStartup() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        UUID alice = UUID.randomUUID();
        session.whitelistAdd(alice, "Alice");
        Point outside = new Point("world", 5_000, 5_000, 5_000);

        assertThat(lobby.shouldRelocateOnJoin(alice, outside)).isFalse();
    }

    @Test
    @DisplayName("a non-whitelisted joiner is never relocated into the lobby")
    void spectatorsAreNotRelocated() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        Point outside = new Point("world", 5_000, 5_000, 5_000);

        assertThat(lobby.shouldRelocateOnJoin(UUID.randomUUID(), outside)).isFalse();
    }

    @Test
    @DisplayName("the lobby centre is reported once the arena is placed, empty before")
    void lobbyCentreReflectsGeometry() {
        assertThat(lobby.lobbyCentre()).isEmpty();

        placeArena();

        assertThat(lobby.lobbyCentre()).contains(LOBBY_CENTRE);
    }

    @Test
    @DisplayName("combat is forbidden while the box is active and either party stands inside it")
    void combatForbiddenInsideTheBox() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        Point inside = insidePoint();
        Point outside = new Point("world", 5_000, 5_000, 5_000);

        assertThat(lobby.forbidsCombatBetween(inside, outside)).isTrue();
        assertThat(lobby.forbidsCombatBetween(outside, inside)).isTrue();
        assertThat(lobby.forbidsCombatBetween(outside, outside)).isFalse();
    }

    @Test
    @DisplayName("combat is never forbidden once the box is no longer active")
    void combatAllowedOnceInactive() {
        placeArena();
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        Point wasInside = new Point("world", ARENA_CENTRE.x(), ARENA_CENTRE.y() + 20, ARENA_CENTRE.z());

        assertThat(lobby.forbidsCombatBetween(wasInside, wasInside)).isFalse();
    }
}
