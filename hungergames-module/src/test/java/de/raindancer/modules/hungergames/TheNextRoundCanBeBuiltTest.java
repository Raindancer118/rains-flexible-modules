package de.raindancer.modules.hungergames;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.model.ArenaLayout;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.ArenaBuildService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.store.ArenaStore;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code /init} works after a round has finished, which is when it is actually run.
 *
 * <h2>The bug, found on a live server</h2>
 * Every completed round left the tournament unable to start another one. {@code /init} answered
 * <em>"the arena could not be built — see the console"</em>, and the console said the round could not move
 * into {@code PREFLIGHT} from {@code FINISHED}. Nothing was wrong with the arena; nothing was wrong with
 * WorldEdit; the message named the wrong thing entirely because the refusal happened before any of that.
 *
 * <h2>Why no test caught it</h2>
 * Because the two halves disagreed and were only ever asked separately.
 * {@link GameControlService#canInit()} lists {@code FINISHED} among the phases {@code /init} may run from,
 * and is tested. {@link GameSession}'s transition table has no {@code FINISHED -> PREFLIGHT} edge, and is
 * tested. Each is right about its own question. Nothing put the two together, and the gate saying yes while
 * the machine says no is a shape that can only fail where they meet.
 *
 * <p>So this test is deliberately about the join: it drives a real session through a whole round and then
 * asks the real {@link ArenaBuildService#claimPreflight()} — not a copy of its two lines — whether the next
 * arena may be built.
 */
class TheNextRoundCanBeBuiltTest {

    private GameSession session;
    private GameControlService control;
    private ArenaBuildService arena;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        control = new GameControlService(session, actor -> false,
                (actor, count) -> true, actor -> true, actor -> true);
        control.settings(HungerGamesSettings.DEFAULTS);

        ArenaStore noArena = mock(ArenaStore.class);
        org.mockito.Mockito.when(noArena.load()).thenReturn(Optional.<ArenaLayout>empty());
        arena = new ArenaBuildService(mock(org.bukkit.plugin.Plugin.class), session,
                mock(de.raindancer.modules.hungergames.visual.Schematics.class), noArena,
                name -> Optional.empty(), uuid -> null, mock(ArenaBuildService.Told.class),
                mock(LogChannel.class));
        arena.settings(HungerGamesSettings.DEFAULTS);
    }

    private void playARoundThrough() {
        for (GamePhase phase : new GamePhase[] {GamePhase.PREFLIGHT, GamePhase.LOBBY, GamePhase.STARTUP,
                GamePhase.READY, GamePhase.RUNNING, GamePhase.FINISHED}) {
            assertThat(session.transitionTo(phase))
                    .as("could not reach %s while setting the test up", phase)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the very first /init of a server works")
    void fromNothing() {
        assertThat(control.canInit()).isTrue();
        assertThat(arena.claimPreflight()).isTrue();
        assertThat(session.phase()).isEqualTo(GamePhase.PREFLIGHT);
    }

    @Test
    @DisplayName("/init works again once a round has finished — the case that was broken")
    void afterARound() {
        playARoundThrough();

        assertThat(control.canInit())
                .as("the gate has always said yes here")
                .isTrue();
        assertThat(arena.claimPreflight())
                .as("and this is what actually said no, on every completed round, on a live server")
                .isTrue();
        assertThat(session.phase()).isEqualTo(GamePhase.PREFLIGHT);
    }

    @Test
    @DisplayName("whenever the gate says /init may run, the build can actually claim the phase")
    void theTwoHalvesAgree() {
        // The general form of the bug rather than the one instance of it. If a phase is ever added to
        // canInit() without an answer here, this fails on that phase rather than on a live server.
        for (GamePhase from : GamePhase.values()) {
            setUp();
            drive(from);
            if (!control.canInit()) {
                continue;
            }
            assertThat(arena.claimPreflight())
                    .as("canInit() says /init may run from %s, and the build cannot leave it", from)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a phase the gate refuses is still refused here, so this is not a way round the machine")
    void midRoundIsStillRefused() {
        drive(GamePhase.RUNNING);

        assertThat(control.canInit()).isFalse();
        assertThat(arena.claimPreflight())
                .as("clearing a round that is being played would put forty people in a dead arena")
                .isFalse();
        assertThat(session.phase()).isEqualTo(GamePhase.RUNNING);
    }

    @Test
    @DisplayName("the tributes and the teams survive into the next round")
    void thePeopleStay() {
        UUID tribute = UUID.randomUUID();
        session.whitelistAdd(tribute, "Katniss");
        playARoundThrough();

        arena.claimPreflight();

        assertThat(session.isWhitelisted(tribute))
                .as("they were entered by hand and the next round is the same evening")
                .isTrue();
    }

    private void drive(GamePhase target) {
        for (GamePhase phase : new GamePhase[] {GamePhase.PREFLIGHT, GamePhase.LOBBY, GamePhase.STARTUP,
                GamePhase.READY, GamePhase.RUNNING, GamePhase.FINISHED}) {
            if (session.phase() == target) {
                return;
            }
            session.transitionTo(phase);
        }
    }
}
