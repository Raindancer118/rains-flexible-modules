package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("bets sized from what a player has")
class StakeRuleTest {

    private final StakeRule rule = new StakeRule();
    private final Money least = Money.of(1);

    @Test
    @DisplayName("an amount is rounded down to 1, 2 or 5 of its size, so the buttons show round bets")
    void nice() {
        assertThat(rule.nice(Money.of(1_234))).isEqualTo(Money.of(1_000));
        assertThat(rule.nice(Money.of(37))).isEqualTo(Money.of(20));
        assertThat(rule.nice(Money.of(5_600))).isEqualTo(Money.of(5_000));
        assertThat(rule.nice(Money.of(2_500_000))).isEqualTo(Money.of(2_000_000));
        assertThat(rule.nice(Money.of(1))).isEqualTo(Money.of(1));
        assertThat(rule.nice(Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a share of the balance, never below the smallest bet and never capped above")
    void share() {
        assertThat(rule.share(Money.of(10_000_000), 0.10, least)).isEqualTo(Money.of(1_000_000));
        assertThat(rule.share(Money.of(50), 0.10, Money.of(10))).as("never below the smallest")
                .isEqualTo(Money.of(10));
        assertThat(rule.share(Money.of(7_777), 1.0, least)).as("all in is everything, not a round number")
                .isEqualTo(Money.of(7_777));
        assertThat(rule.share(Money.of(9_000_000_000L), 1.0, least)).as("however much that is")
                .isEqualTo(Money.of(9_000_000_000L));
    }

    @Test
    @DisplayName("a typed bet is kept as typed, however large")
    void typedIsKept() {
        assertThat(rule.clamp(Money.of(1_000_000), least)).isEqualTo(Money.of(1_000_000));
        assertThat(rule.clamp(Money.ZERO, Money.of(5))).isEqualTo(Money.of(5));
    }

    @Test
    @DisplayName("the bet a game opens with is about a hundredth of the balance")
    void opening() {
        assertThat(rule.opening(Money.of(1_000), least)).isEqualTo(Money.of(10));
        assertThat(rule.opening(Money.of(10_000_000), least)).isEqualTo(Money.of(100_000));
        assertThat(rule.opening(Money.ZERO, Money.of(5))).as("nothing to bet: the smallest bet")
                .isEqualTo(Money.of(5));
    }
}
