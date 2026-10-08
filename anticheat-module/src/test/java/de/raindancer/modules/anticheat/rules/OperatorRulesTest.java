package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.CheckType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OperatorRulesTest {

    @Test
    @DisplayName("a check cycles on → watch only → off → on, and the lists say so")
    void cycle() {
        CheckStateRule rule = new CheckStateRule();
        CheckStateRule.Lists on = new CheckStateRule.Lists(List.of(), List.of());
        assertThat(rule.state(CheckType.FLY, on)).isEqualTo(CheckStateRule.State.ON);

        CheckStateRule.Lists watched = rule.next(CheckType.FLY, on);
        assertThat(rule.state(CheckType.FLY, watched)).isEqualTo(CheckStateRule.State.WATCH);
        assertThat(watched.silent()).containsExactly("fly");

        CheckStateRule.Lists off = rule.next(CheckType.FLY, watched);
        assertThat(rule.state(CheckType.FLY, off)).isEqualTo(CheckStateRule.State.OFF);
        assertThat(off.silent()).isEmpty();
        assertThat(off.disabled()).containsExactly("fly");

        assertThat(rule.state(CheckType.FLY, rule.next(CheckType.FLY, off))).isEqualTo(CheckStateRule.State.ON);
    }

    @Test
    @DisplayName("other checks in the lists are left exactly as they were")
    void othersUntouched() {
        CheckStateRule rule = new CheckStateRule();
        CheckStateRule.Lists lists = new CheckStateRule.Lists(List.of("speed"), List.of("reach"));
        CheckStateRule.Lists after = rule.next(CheckType.FLY, lists);
        assertThat(after.disabled()).containsExactly("speed");
        assertThat(after.silent()).containsExactly("reach", "fly");
    }

    @Test
    @DisplayName("damage is only dampened for somebody clearly suspected in combat")
    void dampen() {
        DampenRule rule = new DampenRule();
        assertThat(rule.multiplier(Map.of(), 50)).isEqualTo(1.0);
        assertThat(rule.multiplier(Map.of(CheckType.REACH, (double) CheckType.REACH.alertAt()), 50)).isEqualTo(1.0);
        assertThat(rule.multiplier(Map.of(CheckType.REACH, CheckType.REACH.alertAt() * 2.0), 50)).isCloseTo(0.5, within(1e-9));
        assertThat(rule.multiplier(Map.of(CheckType.IMPROBABLE, 1.0), 25)).isCloseTo(0.25, within(1e-9));
        assertThat(rule.multiplier(Map.of(CheckType.FLY, 100.0), 50)).as("movement is not combat").isEqualTo(1.0);
    }
}
