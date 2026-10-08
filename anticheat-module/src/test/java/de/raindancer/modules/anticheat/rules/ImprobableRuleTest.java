package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.CheckType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImprobableRuleTest {

    private final ImprobableRule rule = new ImprobableRule();

    @Test
    @DisplayName("one check failing a lot is that check's business, not a pattern")
    void oneCheckIsNotAPattern() {
        assertThat(rule.judge(Map.of(CheckType.FLY, 39.0)).passed()).isTrue();
    }

    @Test
    @DisplayName("many checks each a little past their alert level add up to improbable")
    void manyLittleThings() {
        Map<CheckType, Double> levels = Map.of(CheckType.REACH, 12.0, CheckType.HITBOX, 15.0, CheckType.AUTOCLICKER, 15.0,
                CheckType.CRITICALS, 12.0, CheckType.SPEED, 18.0);
        Judgement judged = rule.judge(levels);
        assertThat(judged.failed()).isTrue();
        assertThat(judged.reason()).contains("Reach").contains("5 checks");
    }

    @Test
    @DisplayName("small levels everywhere, below every alert level, stay quiet")
    void noise() {
        Map<CheckType, Double> levels = Map.of(CheckType.REACH, 1.0, CheckType.HITBOX, 1.0, CheckType.SPEED, 1.0, CheckType.FLY, 1.0);
        assertThat(rule.judge(levels).passed()).isTrue();
    }

    @Test
    @DisplayName("experimental checks and the meta check itself do not count")
    void ignoresSoftChecks() {
        Map<CheckType, Double> levels = Map.of(CheckType.AIM, 100.0, CheckType.POST, 100.0, CheckType.KEEP_SPRINT, 100.0,
                CheckType.IMPROBABLE, 100.0);
        assertThat(rule.judge(levels).passed()).isTrue();
    }
}
