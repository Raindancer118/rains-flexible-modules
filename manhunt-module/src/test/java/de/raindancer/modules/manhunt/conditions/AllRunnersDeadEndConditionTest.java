package de.raindancer.modules.manhunt.conditions;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AllRunnersDeadEndConditionTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID HUNTER = UUID.randomUUID();

    @Test
    void notOverWhileAnyRunnerIsStillAlive() {
        assertThat(AllRunnersDeadEndCondition.everyRunnerHasDied(Set.of(A, B), Set.of(A))).isFalse();
    }

    @Test
    void overOnceEveryRunnerHasDied() {
        assertThat(AllRunnersDeadEndCondition.everyRunnerHasDied(Set.of(A, B), Set.of(A, B))).isTrue();
    }

    @Test
    void aHunterDyingIsIrrelevantToTheRosterItChecks() {
        // The listener itself never adds a Hunter's death to `dead` in the first place — this only
        // pins that the pure check does not accidentally count somebody outside the Runner set.
        assertThat(AllRunnersDeadEndCondition.everyRunnerHasDied(Set.of(A), Set.of(A, HUNTER))).isTrue();
    }

    @Test
    void oneRunnerIsAlreadyTheWholeRoster() {
        assertThat(AllRunnersDeadEndCondition.everyRunnerHasDied(Set.of(A), Set.of(A))).isTrue();
        assertThat(AllRunnersDeadEndCondition.everyRunnerHasDied(Set.of(A), Set.of())).isFalse();
    }
}
