package de.raindancer.modules.manhunt.conditions;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.RunnerDeathRule;
import de.raindancer.modules.manhunt.service.ManhuntLives;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The condition's own contract. <em>When</em> the hunt is over is {@link ManhuntLives}' decision now
 * and is pinned by {@code ManhuntLivesTest} — this class pins what the condition itself promises: a
 * roster it can actually watch, and the pre-death-rules meaning when nobody hands it a board.
 */
class AllRunnersDeadEndConditionTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private final Plugin plugin = mock(Plugin.class);

    @Test
    @DisplayName("a hunt with no Runners is refused outright, not watched forever")
    void emptyRosterIsRefused() {
        assertThatThrownBy(() -> new AllRunnersDeadEndCondition(plugin, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one Runner");
    }

    @Test
    @DisplayName("it names itself for the outcome the session reports")
    void describesItself() {
        assertThat(new AllRunnersDeadEndCondition(plugin, Set.of(A)).describe()).isEqualTo("all-runners-dead");
    }

    @Test
    @DisplayName("with no board handed to it, one death per Runner is still the end — ELIMINATE")
    void defaultsToEliminate() {
        // The same board the condition builds for itself when constructed without one.
        ManhuntLives fallback = new ManhuntLives(
                ManhuntSettings.DEFAULTS.withRunnerDeathRule(RunnerDeathRule.ELIMINATE));

        fallback.record(A);
        assertThat(fallback.allOut(Set.of(A, B))).isFalse();
        fallback.record(B);
        assertThat(fallback.allOut(Set.of(A, B))).isTrue();
    }
}
