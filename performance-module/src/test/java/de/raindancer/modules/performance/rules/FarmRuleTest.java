package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.PerformanceSettings;
import de.raindancer.modules.performance.model.Crowd;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class FarmRuleTest {

    private static final PerformanceSettings LIMITS = PerformanceSettings.farms(true, 16, 50, 150, true);

    private final FarmRule rule = new FarmRule();

    @Test
    @DisplayName("a farm below both limits may grow")
    void belowTheLimits() {
        assertThat(rule.judge(SpawnReason.BREEDING, new Crowd(49, 149), LIMITS)).isEqualTo(FarmRule.Verdict.ALLOW);
    }

    @Test
    @DisplayName("the 51st chicken from an egg is not hatched")
    void tooManyOfOneKind() {
        assertThat(rule.judge(SpawnReason.EGG, new Crowd(50, 60), LIMITS)).isEqualTo(FarmRule.Verdict.TOO_MANY_OF_KIND);
        assertThat(rule.judge(SpawnReason.DISPENSE_EGG, new Crowd(215, 300), LIMITS))
                .isEqualTo(FarmRule.Verdict.TOO_MANY_OF_KIND);
    }

    @Test
    @DisplayName("a mixed farm is held at the limit for all animals together")
    void tooManyAnimals() {
        assertThat(rule.judge(SpawnReason.BREEDING, new Crowd(10, 150), LIMITS))
                .isEqualTo(FarmRule.Verdict.TOO_MANY_ANIMALS);
    }

    @ParameterizedTest
    @EnumSource(value = SpawnReason.class, names = {"NATURAL", "CHUNK_GEN", "SPAWNER_EGG", "COMMAND", "CUSTOM", "SPAWNER"})
    @DisplayName("only farming is limited: natural spawns, spawn eggs and commands are not")
    void otherReasonsAreLeftAlone(SpawnReason reason) {
        assertThat(rule.judge(reason, new Crowd(1000, 1000), LIMITS)).isEqualTo(FarmRule.Verdict.ALLOW);
    }

    @Test
    @DisplayName("switched off, nothing is limited")
    void switchedOff() {
        PerformanceSettings off = PerformanceSettings.farms(false, 16, 50, 150, true);
        assertThat(rule.judge(SpawnReason.EGG, new Crowd(1000, 1000), off)).isEqualTo(FarmRule.Verdict.ALLOW);
    }

    @Test
    @DisplayName("a limit of zero means no limit, not 'none at all'")
    void zeroIsUnlimited() {
        PerformanceSettings noKindLimit = PerformanceSettings.farms(true, 16, 0, 0, true);
        assertThat(rule.judge(SpawnReason.EGG, new Crowd(1000, 1000), noKindLimit)).isEqualTo(FarmRule.Verdict.ALLOW);
    }

    @Test
    @DisplayName("whether counting is needed at all is answered without counting")
    void countingOnlyWhenItMatters() {
        assertThat(rule.limits(SpawnReason.EGG, LIMITS)).isTrue();
        assertThat(rule.limits(SpawnReason.NATURAL, LIMITS)).isFalse();
        assertThat(rule.limits(SpawnReason.EGG, PerformanceSettings.farms(false, 16, 50, 150, true))).isFalse();
    }
}
