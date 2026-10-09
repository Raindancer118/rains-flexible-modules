package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.rules.FundRule.Effect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FundRuleTest {

    private final FundRule rule = new FundRule();

    @Test
    @DisplayName("a fund's effect is read the way staff write it")
    void reading() {
        assertThat(rule.effect("boost 25 24")).contains(new Effect.Boost(25, 24));
        assertThat(rule.effect("command say thanks && give @a cake")).contains(
                new Effect.Commands(List.of("say thanks", "give @a cake")));
        assertThat(rule.effect("")).contains(new Effect.Nothing());
        assertThat(rule.effect("boost lots 2")).isEmpty();
        assertThat(rule.effect("boost 25")).isEmpty();
        assertThat(rule.effect("boost 0 5")).as("a boost of nothing").isEmpty();
        assertThat(rule.effect("boost 25 0")).isEmpty();
        assertThat(rule.effect("command ")).isEmpty();
        assertThat(rule.effect("party")).isEmpty();
    }

    @Test
    @DisplayName("a boost is on from the moment the fund fills for its hours, and off before and after")
    void boostWindow() {
        Effect.Boost boost = new Effect.Boost(25, 2);
        long filled = 1_000_000L;
        assertThat(rule.boosting(boost, filled, filled + 3_600_000L)).isTrue();
        assertThat(rule.boosting(boost, filled, filled + 7_200_001L)).isFalse();
        assertThat(rule.boosting(boost, 0, filled)).as("not filled").isFalse();
    }
}
