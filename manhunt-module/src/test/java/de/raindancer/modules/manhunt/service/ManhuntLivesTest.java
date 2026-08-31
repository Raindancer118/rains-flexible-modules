package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.RunnerDeathRule;
import de.raindancer.modules.manhunt.service.ManhuntLives.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What a Runner's death costs them. Pure bookkeeping, no Bukkit — see the class itself. */
class ManhuntLivesTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    private static ManhuntLives with(RunnerDeathRule rule, int lives) {
        return new ManhuntLives(ManhuntSettings.DEFAULTS.withRunnerDeathRule(rule).withRunnerLives(lives));
    }

    @Nested
    @DisplayName("RESPAWN")
    class Respawning {

        @Test
        @DisplayName("a death costs nothing and nobody is ever out")
        void deathsAreFree() {
            ManhuntLives lives = with(RunnerDeathRule.RESPAWN, 1);

            assertThat(lives.record(ANNA)).isEqualTo(Verdict.RESPAWNED);
            assertThat(lives.record(ANNA)).isEqualTo(Verdict.RESPAWNED);
            assertThat(lives.isOut(ANNA)).isFalse();
        }

        @Test
        @DisplayName("nobody is ever all out, however many times they die")
        void neverAllOut() {
            ManhuntLives lives = with(RunnerDeathRule.RESPAWN, 1);
            lives.record(ANNA);

            assertThat(lives.allOut(Set.of(ANNA))).isFalse();
        }
    }

    @Nested
    @DisplayName("ELIMINATE")
    class Eliminating {

        @Test
        @DisplayName("one death and the Runner is out")
        void oneDeathIsOut() {
            ManhuntLives lives = with(RunnerDeathRule.ELIMINATE, 5);

            assertThat(lives.record(ANNA)).isEqualTo(Verdict.ELIMINATED);
            assertThat(lives.isOut(ANNA)).isTrue();
        }

        @Test
        @DisplayName("the configured number of lives is ignored — ELIMINATE means one")
        void livesAreIgnored() {
            assertThat(with(RunnerDeathRule.ELIMINATE, 9).livesLeft(ANNA)).isEqualTo(1);
        }

        @Test
        @DisplayName("dying again once out changes nothing")
        void deathAfterEliminationIsIdempotent() {
            ManhuntLives lives = with(RunnerDeathRule.ELIMINATE, 1);
            lives.record(ANNA);

            assertThat(lives.record(ANNA)).isEqualTo(Verdict.ELIMINATED);
            assertThat(lives.livesLeft(ANNA)).isZero();
        }
    }

    @Nested
    @DisplayName("LIVES")
    class Counting {

        @Test
        @DisplayName("a Runner starts on the configured number")
        void startsFull() {
            assertThat(with(RunnerDeathRule.LIVES, 3).livesLeft(ANNA)).isEqualTo(3);
        }

        @Test
        @DisplayName("each death takes one, and the last one puts them out")
        void countsDown() {
            ManhuntLives lives = with(RunnerDeathRule.LIVES, 3);

            assertThat(lives.record(ANNA)).isEqualTo(Verdict.RESPAWNED);
            assertThat(lives.livesLeft(ANNA)).isEqualTo(2);
            assertThat(lives.record(ANNA)).isEqualTo(Verdict.RESPAWNED);
            assertThat(lives.livesLeft(ANNA)).isEqualTo(1);
            assertThat(lives.record(ANNA)).isEqualTo(Verdict.ELIMINATED);
            assertThat(lives.livesLeft(ANNA)).isZero();
            assertThat(lives.isOut(ANNA)).isTrue();
        }

        @Test
        @DisplayName("one Runner's deaths are not another's")
        void perRunner() {
            ManhuntLives lives = with(RunnerDeathRule.LIVES, 2);
            lives.record(ANNA);

            assertThat(lives.livesLeft(ANNA)).isEqualTo(1);
            assertThat(lives.livesLeft(BEN)).isEqualTo(2);
        }

        @Test
        @DisplayName("a nonsensical life count is clamped rather than trusted")
        void clamped() {
            assertThat(with(RunnerDeathRule.LIVES, 0).livesLeft(ANNA)).isEqualTo(1);
            assertThat(with(RunnerDeathRule.LIVES, 99).livesLeft(ANNA)).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("allOut")
    class AllOut {

        @Test
        @DisplayName("not while one Runner is still standing")
        void notWhileOneStands() {
            ManhuntLives lives = with(RunnerDeathRule.ELIMINATE, 1);
            lives.record(ANNA);

            assertThat(lives.allOut(Set.of(ANNA, BEN))).isFalse();
        }

        @Test
        @DisplayName("once the last Runner is out")
        void whenTheLastIsOut() {
            ManhuntLives lives = with(RunnerDeathRule.ELIMINATE, 1);
            lives.record(ANNA);
            lives.record(BEN);

            assertThat(lives.allOut(Set.of(ANNA, BEN))).isTrue();
        }

        @Test
        @DisplayName("an empty roster is not a win — there was nobody to beat")
        void emptyRosterIsNotAWin() {
            assertThat(with(RunnerDeathRule.ELIMINATE, 1).allOut(Set.of())).isFalse();
        }

        @Test
        @DisplayName("who is still standing is answerable, for the narration")
        void standing() {
            ManhuntLives lives = with(RunnerDeathRule.ELIMINATE, 1);
            lives.record(ANNA);

            assertThat(lives.stillIn(Set.of(ANNA, BEN))).containsExactly(BEN);
        }
    }

    @Test
    @DisplayName("a fresh hunt starts everybody on full lives again")
    void resetting() {
        ManhuntLives lives = with(RunnerDeathRule.LIVES, 2);
        lives.record(ANNA);
        lives.reset();

        assertThat(lives.livesLeft(ANNA)).isEqualTo(2);
        assertThat(lives.isOut(ANNA)).isFalse();
    }

    @Test
    @DisplayName("live settings are re-read, not captured once")
    void settingsAreLive() {
        ManhuntLives lives = with(RunnerDeathRule.RESPAWN, 1);
        lives.settings(ManhuntSettings.DEFAULTS.withRunnerDeathRule(RunnerDeathRule.ELIMINATE));

        assertThat(lives.record(ANNA)).isEqualTo(Verdict.ELIMINATED);
    }
}
