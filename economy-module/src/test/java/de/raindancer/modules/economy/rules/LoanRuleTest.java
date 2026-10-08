package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.model.LoanRefusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoanRuleTest {

    private static final long DAY = LoanRule.DAY;
    private final LoanRule rule = new LoanRule();
    private final UUID alice = UUID.randomUUID();

    @Test
    @DisplayName("interest is added once when borrowing, rounded up to a whole coin")
    void owed() {
        assertThat(rule.owedFor(Money.of(1_000), 0.10)).isEqualTo(Money.of(1_100));
        assertThat(rule.owedFor(Money.of(15), 0.10)).as("1.5 rounds up").isEqualTo(Money.of(17));
        assertThat(rule.owedFor(Money.of(500), 0)).isEqualTo(Money.of(500));
    }

    @Test
    @DisplayName("a loan is refused while one is open, below the least and above the most")
    void refusals() {
        Money least = Money.of(100);
        Money most = Money.of(10_000);
        assertThat(rule.refusal(Money.of(500), least, most, false)).isEmpty();
        assertThat(rule.refusal(Money.of(500), least, most, true)).contains(LoanRefusal.HAS_LOAN);
        assertThat(rule.refusal(Money.of(99), least, most, false)).contains(LoanRefusal.TOO_LITTLE);
        assertThat(rule.refusal(Money.of(10_001), least, most, false)).contains(LoanRefusal.TOO_MUCH);
        assertThat(rule.refusal(Money.ZERO, least, most, false)).contains(LoanRefusal.TOO_LITTLE);
    }

    @Test
    @DisplayName("late fees: one per whole day overdue, on what is still owed, never twice for the same day")
    void lateFees() {
        Loan loan = new Loan(alice, "Alice", Money.of(1_000), Money.of(1_000), 0, 7 * DAY, 0);
        assertThat(rule.withLateFees(loan, 7 * DAY + DAY - 1, 0.02).owed()).as("not a whole day yet")
                .isEqualTo(Money.of(1_000));
        Loan oneDay = rule.withLateFees(loan, 8 * DAY, 0.02);
        assertThat(oneDay.owed()).isEqualTo(Money.of(1_020));
        assertThat(rule.withLateFees(oneDay, 8 * DAY + 5, 0.02)).as("the same day is not charged again")
                .isEqualTo(oneDay);
        assertThat(rule.withLateFees(oneDay, 10 * DAY, 0.02).owed()).as("compounds per day")
                .isEqualTo(Money.of(1_062));
        assertThat(rule.withLateFees(loan, 3 * DAY, 0.02)).as("not due yet").isEqualTo(loan);
        assertThat(rule.withLateFees(loan, 30 * DAY, 0).owed()).as("no fee set").isEqualTo(Money.of(1_000));
    }

    @Test
    @DisplayName("the bank collects what the balance holds, up to what is owed")
    void collecting() {
        assertThat(rule.collect(Money.of(300), Money.of(1_000))).isEqualTo(Money.of(300));
        assertThat(rule.collect(Money.of(3_000), Money.of(1_000))).isEqualTo(Money.of(1_000));
        assertThat(rule.collect(Money.ZERO, Money.of(1_000))).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("the offered amounts are round, rising, never below the least, and end with the most")
    void offers() {
        assertThat(rule.offers(Money.of(100), Money.of(10_000)))
                .containsExactly(Money.of(1_000), Money.of(2_000), Money.of(5_000), Money.of(10_000));
        assertThat(rule.offers(Money.of(3_000), Money.of(10_000)))
                .containsExactly(Money.of(3_000), Money.of(5_000), Money.of(10_000));
        assertThat(rule.offers(Money.of(100), Money.of(100))).containsExactly(Money.of(100));
    }
}
