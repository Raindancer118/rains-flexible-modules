package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.FlowRule.Flow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowRuleTest {

    private final FlowRule rule = new FlowRule();

    @Test
    @DisplayName("money arriving from nowhere is printed; money leaving to nowhere is destroyed")
    void fromNowhere() {
        assertThat(rule.classify(false, false, TransactionKind.REWARD, 100)).isEqualTo(Flow.CREATED);
        assertThat(rule.classify(false, false, TransactionKind.SELL, 100)).isEqualTo(Flow.CREATED);
        assertThat(rule.classify(false, false, TransactionKind.PLUGIN, 100)).isEqualTo(Flow.CREATED);
        assertThat(rule.classify(false, false, TransactionKind.BUY, -100)).isEqualTo(Flow.DESTROYED);
        assertThat(rule.classify(false, false, TransactionKind.TAX, -100)).isEqualTo(Flow.DESTROYED);
        assertThat(rule.classify(false, false, TransactionKind.GAMBLE, -100)).isEqualTo(Flow.DESTROYED);
    }

    @Test
    @DisplayName("between players, into cash and through pots is only moving money")
    void moving() {
        assertThat(rule.classify(false, true, TransactionKind.PAY, -100)).isEqualTo(Flow.MOVED);
        assertThat(rule.classify(false, true, TransactionKind.GAMBLE, 100)).as("a duel").isEqualTo(Flow.MOVED);
        assertThat(rule.classify(false, false, TransactionKind.WITHDRAW, -100)).isEqualTo(Flow.MOVED);
        assertThat(rule.classify(false, false, TransactionKind.DEPOSIT, 100)).isEqualTo(Flow.MOVED);
        assertThat(rule.classify(true, true, TransactionKind.LOTTERY, -100)).isEqualTo(Flow.MOVED);
    }

    @Test
    @DisplayName("a pot's fee or cut is destroyed, even with a player named on the line")
    void potFees() {
        assertThat(rule.classify(true, true, TransactionKind.FEE, -100)).isEqualTo(Flow.DESTROYED);
        assertThat(rule.classify(true, false, TransactionKind.TAX, -100)).isEqualTo(Flow.DESTROYED);
    }
}
