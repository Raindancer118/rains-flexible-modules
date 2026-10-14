package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShardPriceRuleTest {

    private final ShardPriceRule rule = new ShardPriceRule(List.of("8", "24", "64"), new Money(25_000));

    @Test
    @DisplayName("a book takes the level's shards, and past the list each level doubles the last")
    void shards() {
        assertThat(rule.shards(1)).isEqualTo(8);
        assertThat(rule.shards(3)).isEqualTo(64);
        assertThat(rule.shards(4)).isEqualTo(128);
        assertThat(rule.shards(5)).isEqualTo(256);
        assertThat(rule.shards(0)).as("no level, no book").isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("and the money per level on top")
    void money() {
        assertThat(rule.money(1)).isEqualTo(new Money(25_000));
        assertThat(rule.money(3)).isEqualTo(new Money(75_000));
    }

    @Test
    @DisplayName("a mangled list falls back to the built-in steps rather than giving books away")
    void mangled() {
        ShardPriceRule broken = new ShardPriceRule(List.of("eight", "-3"), new Money(1));
        assertThat(broken.shards(1)).isEqualTo(8);
        assertThat(broken.shards(2)).isEqualTo(24);
    }
}
