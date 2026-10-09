package de.raindancer.modules.jobs.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RewardRuleTest {

    private final RewardRule rule = new RewardRule();
    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();

    @Test
    @DisplayName("a reached goal pays the whole reward, each their share: half the goal, half the money")
    void shares() {
        Map<UUID, Integer> given = new LinkedHashMap<>();
        given.put(ana, 100);
        given.put(bo, 60);
        given.put(cy, 40);
        Map<UUID, Money> paid = rule.payouts(given, 200, Money.of(10_000));
        assertThat(paid).containsEntry(ana, Money.of(5_000)).containsEntry(bo, Money.of(3_000))
                .containsEntry(cy, Money.of(2_000));
    }

    @Test
    @DisplayName("a missed goal pays for what was reached: 60% of it, 60% of the reward, shared the same way")
    void missed() {
        Map<UUID, Money> paid = rule.payouts(Map.of(ana, 90, bo, 30), 200, Money.of(10_000));
        assertThat(paid).containsEntry(ana, Money.of(4_500)).containsEntry(bo, Money.of(1_500));
    }

    @Test
    @DisplayName("shares round down — never more paid out than the reward — and nobody is paid for nothing")
    void rounding() {
        Map<UUID, Money> paid = rule.payouts(Map.of(ana, 1, bo, 1, cy, 1), 3, Money.of(100));
        assertThat(paid.values()).allSatisfy(money -> assertThat(money).isEqualTo(Money.of(33)));
        assertThat(rule.payouts(Map.of(ana, 0), 3, Money.of(100))).isEmpty();
        assertThat(rule.payouts(Map.of(), 3, Money.of(100))).isEmpty();
        assertThat(rule.payouts(Map.of(ana, 5), 3, Money.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("the reward is the median balance: a few rich players do not move it")
    void median() {
        assertThat(rule.median(List.of(Money.of(100), Money.of(300), Money.of(1_000_000)))).isEqualTo(Money.of(300));
        assertThat(rule.median(List.of(Money.of(100), Money.of(300), Money.of(500), Money.of(9_999_999))))
                .isEqualTo(Money.of(400));
        assertThat(rule.median(List.of())).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("the reward: the median, times the owner's percent, within their least and most")
    void pool() {
        assertThat(rule.pool(Money.of(4_000), 150, Money.of(100), Money.ZERO)).isEqualTo(Money.of(6_000));
        assertThat(rule.pool(Money.of(10), 100, Money.of(100), Money.ZERO)).isEqualTo(Money.of(100));
        assertThat(rule.pool(Money.of(90_000), 100, Money.of(100), Money.of(50_000))).isEqualTo(Money.of(50_000));
    }

    @Test
    @DisplayName("the pay scale shrinks or grows a reward, rounded down; 100 leaves it alone")
    void scaled() {
        assertThat(rule.scaled(Money.of(2_001), 100)).isEqualTo(Money.of(2_001));
        assertThat(rule.scaled(Money.of(2_001), 50)).isEqualTo(Money.of(1_000));
        assertThat(rule.scaled(Money.of(2_000), 150)).isEqualTo(Money.of(3_000));
        assertThat(rule.scaled(Money.of(2_000), 0)).isEqualTo(Money.ZERO);
        assertThat(rule.scaled(Money.of(2_000), -5)).isEqualTo(Money.ZERO);
    }
}
