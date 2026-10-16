package de.raindancer.modules.oneblock.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardRuleTest {

    private final ShardRule rule = new ShardRule();

    @Test
    @DisplayName("an award every n-th break of the island, counted over all its blocks")
    void everyNth() {
        assertThat(rule.earnedAt(Contents.content(), 9)).isZero();
        assertThat(rule.earnedAt(Contents.content(), 10)).isEqualTo(1);
        assertThat(rule.earnedAt(Contents.content(), 11)).isZero();
        assertThat(rule.earnedAt(Contents.content(), 20)).isEqualTo(1);
        assertThat(rule.earnedAt(Contents.content(), 0)).isZero();
    }

    @Test
    @DisplayName("an island that came over gets what its breaks would have earned here")
    void migrated() {
        assertThat(rule.earnedOver(Contents.content(), 2550)).isEqualTo(255);
        assertThat(rule.earnedOver(Contents.content(), 9)).isZero();
        assertThat(rule.earnedOver(Contents.content(), -4)).isZero();
    }
}
