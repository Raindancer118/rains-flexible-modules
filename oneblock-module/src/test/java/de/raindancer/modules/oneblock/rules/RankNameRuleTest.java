package de.raindancer.modules.oneblock.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankNameRuleTest {

    private final RankNameRule rule = new RankNameRule();

    @Test
    @DisplayName("BentoBox's rank numbers read as the names players know, in-between values rounding down")
    void names() {
        assertThat(rule.keyFor(1000)).isEqualTo("oneblock.sidebar.rank.owner");
        assertThat(rule.keyFor(900)).isEqualTo("oneblock.sidebar.rank.sub-owner");
        assertThat(rule.keyFor(500)).isEqualTo("oneblock.sidebar.rank.member");
        assertThat(rule.keyFor(650)).isEqualTo("oneblock.sidebar.rank.member");
        assertThat(rule.keyFor(400)).isEqualTo("oneblock.sidebar.rank.trusted");
        assertThat(rule.keyFor(200)).isEqualTo("oneblock.sidebar.rank.coop");
        assertThat(rule.keyFor(0)).isEqualTo("oneblock.sidebar.rank.visitor");
        assertThat(rule.keyFor(-1)).isEqualTo("oneblock.sidebar.rank.banned");
    }
}
