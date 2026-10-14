package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.CheckType;
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
        assertThat(rule.state(CheckType.GRIM_MOVEMENT, on)).isEqualTo(CheckStateRule.State.ON);

        CheckStateRule.Lists watched = rule.next(CheckType.GRIM_MOVEMENT, on);
        assertThat(rule.state(CheckType.GRIM_MOVEMENT, watched)).isEqualTo(CheckStateRule.State.WATCH);
        assertThat(watched.silent()).containsExactly("movement");

        CheckStateRule.Lists off = rule.next(CheckType.GRIM_MOVEMENT, watched);
        assertThat(rule.state(CheckType.GRIM_MOVEMENT, off)).isEqualTo(CheckStateRule.State.OFF);
        assertThat(off.silent()).isEmpty();
        assertThat(off.disabled()).containsExactly("movement");

        assertThat(rule.state(CheckType.GRIM_MOVEMENT, rule.next(CheckType.GRIM_MOVEMENT, off))).isEqualTo(CheckStateRule.State.ON);
    }

    @Test
    @DisplayName("other checks in the lists are left exactly as they were")
    void othersUntouched() {
        CheckStateRule rule = new CheckStateRule();
        CheckStateRule.Lists lists = new CheckStateRule.Lists(List.of("speed"), List.of("reach"));
        CheckStateRule.Lists after = rule.next(CheckType.GRIM_MOVEMENT, lists);
        assertThat(after.disabled()).containsExactly("speed");
        assertThat(after.silent()).containsExactly("reach", "movement");
    }
}
