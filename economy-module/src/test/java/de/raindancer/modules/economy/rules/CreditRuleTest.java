package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CreditHistory;
import de.raindancer.modules.economy.model.TransactionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a personal loan limit, from what somebody has, earns, spends, gambles and pays back")
class CreditRuleTest {

    private final CreditRule rule = new CreditRule();
    private static final Money NO_CAP = Money.ZERO;

    /** in/out pairs per kind, in whole coins. */
    private static CreditHistory history(int onTime, int late, Object... kindInOut) {
        Map<TransactionKind, Money> in = new EnumMap<>(TransactionKind.class);
        Map<TransactionKind, Money> out = new EnumMap<>(TransactionKind.class);
        for (int i = 0; i < kindInOut.length; i += 3) {
            TransactionKind kind = (TransactionKind) kindInOut[i];
            in.put(kind, Money.of(((Number) kindInOut[i + 1]).longValue()));
            out.put(kind, Money.of(((Number) kindInOut[i + 2]).longValue()));
        }
        return new CreditHistory(in, out, onTime, late, CreditHistory.Recent.NONE);
    }

    private Money limit(long balance, CreditHistory history) {
        return rule.limit(new CreditRule.Standing(Money.of(balance), history), NO_CAP).amount();
    }

    @Test
    @DisplayName("a newcomer with nothing can borrow nothing; what they have counts in full")
    void fromBalance() {
        assertThat(limit(0, CreditHistory.EMPTY)).isEqualTo(Money.ZERO);
        assertThat(limit(10_000, CreditHistory.EMPTY)).isEqualTo(Money.of(10_000));
    }

    @Test
    @DisplayName("everything ever earned counts a quarter")
    void earned() {
        CreditHistory worker = history(0, 0, TransactionKind.SELL, 40_000, 0, TransactionKind.WAGE, 40_000, 0);
        assertThat(limit(10_000, worker)).isEqualTo(Money.of(30_000));
    }

    @Test
    @DisplayName("spending most of what you earn lowers it, down to half")
    void spending() {
        CreditHistory spender = history(0, 0, TransactionKind.SELL, 40_000, 0, TransactionKind.BUY, 0, 120_000);
        // capacity 10,000 + 10,000 = 20,000; earned 40k of 160k moved → ×(0.5 + 0.5·0.25) = 0.625
        assertThat(limit(10_000, spender)).isEqualTo(Money.of(12_000));
        CreditHistory saver = history(0, 0, TransactionKind.SELL, 40_000, 0);
        assertThat(limit(10_000, saver)).isGreaterThan(limit(10_000, spender));
    }

    @Test
    @DisplayName("money gambled away lowers it against what was earned, never below a quarter; winning adds nothing")
    void gambling() {
        CreditHistory loser = history(0, 0, TransactionKind.SELL, 40_000, 0, TransactionKind.GAMBLE, 10_000, 30_000);
        // gambled away 20k of 40k earned → ×0.5 of 20,000
        assertThat(limit(10_000, loser)).isEqualTo(Money.of(10_000));
        CreditHistory broke = history(0, 0, TransactionKind.GAMBLE, 0, 1_000_000);
        assertThat(limit(10_000, broke)).isEqualTo(Money.of(2_500));
        CreditHistory lucky = history(0, 0, TransactionKind.GAMBLE, 50_000, 10_000);
        assertThat(limit(10_000, lucky)).as("a lifetime of winning counts like earning").isEqualTo(Money.of(20_000));
    }

    @Test
    @DisplayName("paying loans back on time raises it, late ones cut it")
    void record() {
        assertThat(limit(10_000, history(5, 0))).isEqualTo(Money.of(15_000));
        assertThat(limit(10_000, history(0, 2))).isEqualTo(Money.of(4_000));
        assertThat(limit(10_000, history(50, 0))).as("at most double").isEqualTo(Money.of(20_000));
        assertThat(limit(10_000, history(0, 9))).as("at least a quarter").isEqualTo(Money.of(2_500));
    }

    @Test
    @DisplayName("money passed back and forth between friends earns nobody anything")
    void transfersAreNetted() {
        CreditHistory cycled = history(0, 0, TransactionKind.PAY, 1_000_000, 1_000_000);
        assertThat(limit(10_000, cycled)).isEqualTo(Money.of(10_000));
        CreditHistory paid = history(0, 0, TransactionKind.PAY, 40_000, 0);
        assertThat(limit(10_000, paid)).isEqualTo(Money.of(20_000));
    }

    @Test
    @DisplayName("the server's largest loan caps everybody, and the limit is a round number")
    void capAndRounding() {
        assertThat(rule.limit(new CreditRule.Standing(Money.of(500_000_000L), CreditHistory.EMPTY),
                Money.of(100_000_000L)).amount()).isEqualTo(Money.of(100_000_000L));
        assertThat(limit(12_345, CreditHistory.EMPTY)).isEqualTo(Money.of(12_000));
        assertThat(limit(-500, CreditHistory.EMPTY)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("the breakdown says why")
    void breakdown() {
        CreditRule.Limit limit = rule.limit(new CreditRule.Standing(Money.of(10_000),
                history(1, 0, TransactionKind.SELL, 4_000, 0, TransactionKind.GAMBLE, 0, 1_000)), NO_CAP);
        assertThat(limit.earned()).isEqualTo(Money.of(4_000));
        assertThat(limit.gambledAway()).isEqualTo(Money.of(1_000));
        assertThat(limit.capacity()).isEqualTo(Money.of(11_000));
        assertThat(limit.gambling()).isEqualTo(0.75);
        assertThat(limit.record()).isEqualTo(1.1);
    }

    private static CreditHistory recently(CreditHistory lifetime, long earned, long staked, long won) {
        return new CreditHistory(lifetime.in(), lifetime.out(), lifetime.repaidOnTime(), lifetime.repaidLate(),
                new CreditHistory.Recent(Money.of(earned), Money.of(staked), Money.of(won)));
    }

    @Test
    @DisplayName("losing lately cuts it hard, however much was won before; winning lately changes nothing")
    void recentLosses() {
        CreditHistory winner = history(0, 0, TransactionKind.GAMBLE, 50_000, 10_000);
        assertThat(limit(10_000, recently(winner, 0, 0, 0))).isEqualTo(Money.of(20_000));
        // lately: staked 9,000, won 1,000 → 8,000 gone against 10,000 balance + 0 earned → ×0.25 (floor)
        assertThat(limit(10_000, recently(winner, 0, 9_000, 1_000))).isEqualTo(Money.of(5_000));
        // lately: 2,500 gone against 10,000 → ×0.75
        assertThat(limit(10_000, recently(winner, 0, 3_000, 500))).isEqualTo(Money.of(15_000));
        assertThat(limit(10_000, recently(winner, 0, 1_000, 9_000))).isEqualTo(Money.of(20_000));
    }
}
