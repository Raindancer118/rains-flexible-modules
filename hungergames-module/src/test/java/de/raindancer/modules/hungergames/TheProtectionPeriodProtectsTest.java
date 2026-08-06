package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.listener.GracePeriodListener;
import de.raindancer.modules.hungergames.model.GamePhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the protection period protects.
 *
 * <h2>The bug this was written for, reported by somebody being eaten</h2>
 * "Although it says I am protected during the protection period, I am not, and zombies can just kill me."
 *
 * <p>The period was measured, announced and displayed. {@code GameTimerService.isGraceActive()} answered
 * correctly the whole time and <b>nothing ever asked it</b>: no listener cancelled a hit. So the round told
 * forty people they were safe for sixty seconds and then let the difficulty the arena had just been set to do
 * what it does.
 *
 * <p>Worse than not having the feature. A player who is told nothing takes cover; a player who is told they are
 * protected walks into the open — which is the whole point of the announcement, and exactly what got them
 * killed.
 *
 * <h2>What is refused and what is not</h2>
 * The protection is against the <em>world</em>, not against everything. Tributes cannot hurt each other during
 * it either — that is what makes the opening scramble a footrace rather than a fight, which is what the setting
 * is for. But falling into the void still kills, and so does a gamemaster eliminating somebody by hand: the
 * first because nothing can save them and pretending otherwise is a player stuck at the bottom of the world,
 * the second because it is a deliberate act by somebody who can see what they are doing.
 */
class TheProtectionPeriodProtectsTest {

    private static final UUID TRIBUTE = UUID.randomUUID();

    private GracePeriodListener listener(boolean graceRunning, GamePhase phase) {
        return new GracePeriodListener(() -> graceRunning, () -> phase, uuid -> true);
    }

    @Nested
    @DisplayName("while it is running")
    class DuringGrace {

        @Test
        @DisplayName("a zombie cannot kill a tribute")
        void mobsAreRefused() {
            assertThat(listener(true, GamePhase.RUNNING).wouldCancel(TRIBUTE, "ENTITY_ATTACK"))
                    .as("this is the whole report: told they were protected, eaten anyway")
                    .isTrue();
        }

        @Test
        @DisplayName("nor can fire, drowning, suffocation or a cactus")
        void theWorldIsRefused() {
            for (String cause : java.util.List.of("FIRE", "FIRE_TICK", "DROWNING", "SUFFOCATION",
                    "CONTACT", "LAVA", "FALL", "STARVATION")) {
                assertThat(listener(true, GamePhase.RUNNING).wouldCancel(TRIBUTE, cause))
                        .as("cause %s", cause)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("nor another tribute, because that is what the period is for")
        void tributesCannotFightEitherr() {
            // A footrace to the cornucopia rather than a fight at it — which is the only reason to have a
            // protection period at all.
            assertThat(listener(true, GamePhase.RUNNING).wouldCancel(TRIBUTE, "ENTITY_ATTACK")).isTrue();
            assertThat(listener(true, GamePhase.RUNNING).wouldCancel(TRIBUTE, "PROJECTILE")).isTrue();
        }

        @Test
        @DisplayName("the void still kills, because nothing can save them from it")
        void theVoidIsHonest() {
            assertThat(listener(true, GamePhase.RUNNING).wouldCancel(TRIBUTE, "VOID"))
                    .as("a cancelled void death is a player stuck below the world for the rest of the round")
                    .isFalse();
        }

        @Test
        @DisplayName("somebody who is not a tribute is not protected")
        void onlyTributes() {
            GracePeriodListener onlyTributes =
                    new GracePeriodListener(() -> true, () -> GamePhase.RUNNING, uuid -> false);

            assertThat(onlyTributes.wouldCancel(TRIBUTE, "ENTITY_ATTACK"))
                    .as("a spectator or a staff member is not in the round and is not covered by its rules")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("once it is over")
    class AfterGrace {

        @Test
        @DisplayName("everything hurts again")
        void nothingIsRefused() {
            assertThat(listener(false, GamePhase.RUNNING).wouldCancel(TRIBUTE, "ENTITY_ATTACK"))
                    .as("a protection that outlived its period would make the round unwinnable")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("outside a running round")
    class NotRunning {

        @Test
        @DisplayName("the lobby's own rules apply, not this one")
        void thisOneStandsAside() {
            // LobbyListener already refuses combat in the glass box, and two handlers cancelling the same hit
            // for different reasons is how one of them silently stops mattering.
            for (GamePhase phase : java.util.List.of(GamePhase.LOBBY, GamePhase.READY,
                    GamePhase.NOT_INITIALIZED, GamePhase.FINISHED)) {
                assertThat(listener(true, phase).wouldCancel(TRIBUTE, "ENTITY_ATTACK"))
                        .as("phase %s", phase)
                        .isFalse();
            }
        }
    }
}
