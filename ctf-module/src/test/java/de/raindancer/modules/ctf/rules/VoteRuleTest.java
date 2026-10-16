package de.raindancer.modules.ctf.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("counting the lobby's votes")
class VoteRuleTest {

    private final VoteRule rule = new VoteRule();

    @Test
    @DisplayName("most votes wins")
    void most() {
        assertThat(rule.winner(List.of("a", "b", "c"), Map.of("a", 1, "b", 3), new Random(1))).isEqualTo("b");
    }

    @Test
    @DisplayName("nobody voted: one of the options, picked at random")
    void none() {
        assertThat(rule.winner(List.of("a", "b"), Map.of(), new Random(1))).isIn("a", "b");
    }

    @Test
    @DisplayName("a tie is settled among the tied only")
    void tie() {
        for (int seed = 0; seed < 20; seed++) {
            assertThat(rule.winner(List.of("a", "b", "c"), Map.of("a", 2, "c", 2, "b", 1), new Random(seed)))
                    .isIn("a", "c");
        }
    }

    @Test
    @DisplayName("the ballot offers a few maps, not always the same ones, never the last one played if avoidable")
    void ballot() {
        List<String> picked = rule.ballot(List.of("a", "b", "c", "d", "e"), 3, "a", new Random(5));
        assertThat(picked).hasSize(3).doesNotContain("a").doesNotHaveDuplicates();
        assertThat(rule.ballot(List.of("a"), 3, "a", new Random(5))).containsExactly("a");
    }
}
