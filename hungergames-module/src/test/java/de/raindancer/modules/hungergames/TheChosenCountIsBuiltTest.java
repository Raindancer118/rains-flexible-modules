package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the arena is built for the number somebody actually chose.
 *
 * <h2>The bug this was written for, seen on a live server</h2>
 * A gamemaster picked 42 in the number chooser and got an arena with two platforms.
 *
 * <p>{@code GameControlService.init(actor, playerCount)} validated the count against
 * {@link GameControlService#MIN_PLAYERS} and {@link GameControlService#MAX_PLAYERS} — and then called
 * {@code initStage.run(actor)}, because {@code Stage} carried only the actor. The number was checked and
 * thrown away one line later. {@code ArenaBuildService} then re-derived it from the tribute register, which
 * on that server was empty, so {@code Math.max(2, 0)} gave two.
 *
 * <p>The comment above that line said the register was "the same number /init itself checked a moment
 * earlier". It was not, and writing it down did not make it so. A validated argument that reaches nothing is
 * worse than no validation, because the error message quotes a range the value never had to be inside.
 */
class TheChosenCountIsBuiltTest {

    /** Every count a stage was actually asked to build for. */
    private final List<Integer> built = new ArrayList<>();
    private GameSession session;
    private GameControlService control;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(1));
        control = new GameControlService(session, actor -> false,
                (actor, count) -> {
                    built.add(count);
                    return true;
                },
                actor -> true, actor -> true);
    }

    @Test
    @DisplayName("the count that was chosen is the count that is built")
    void theNumberArrives() {
        assertThat(control.init(UUID.randomUUID(), 42)).isEmpty();

        assertThat(built)
                .as("42 was chosen in the chooser and two platforms were pasted, because Stage carried only "
                        + "the actor and the count was re-derived from an empty register")
                .containsExactly(42);
    }

    @Test
    @DisplayName("an empty tribute register does not shrink the arena")
    void theRegisterIsNotTheAnswer() {
        // The register is who MAY play. The arena size is how many platforms to paste, and it is chosen
        // before the sign-up sheet is finished — which is exactly when this went wrong.
        assertThat(session.participants().all()).isEmpty();

        control.init(UUID.randomUUID(), 24);

        assertThat(built).containsExactly(24);
    }

    @Test
    @DisplayName("a count outside the range never reaches the build")
    void theBoundsStillHold() {
        assertThat(control.init(UUID.randomUUID(), 1)).isPresent();
        assertThat(control.init(UUID.randomUUID(), GameControlService.MAX_PLAYERS + 1)).isPresent();

        assertThat(built)
                .as("validating a value and then not passing it on is the bug; validating it and refusing is "
                        + "the point")
                .isEmpty();
    }

    @Test
    @DisplayName("both ends of the range are buildable")
    void theEndsAreAllowed() {
        assertThat(control.init(UUID.randomUUID(), GameControlService.MIN_PLAYERS)).isEmpty();
        control.prepareNextRound();
        assertThat(control.init(UUID.randomUUID(), GameControlService.MAX_PLAYERS)).isEmpty();

        assertThat(built).containsExactly(GameControlService.MIN_PLAYERS, GameControlService.MAX_PLAYERS);
    }
}
