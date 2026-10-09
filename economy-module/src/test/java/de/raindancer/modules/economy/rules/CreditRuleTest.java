package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CreditHistory;
import de.raindancer.modules.economy.model.TransactionKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a personal loan limit, from what somebody has and what they did in their last hours of play")
class CreditRuleTest {

    private final CreditRule rule = new CreditRule();
    private static final Money NO_CAP = Money.ZERO;

    /** What happened lately, in whole coins: earned, spent, staked, won, received from players, paid to players. */
    private static CreditHistory lately(long earned, long spent, long staked, long won, long received, long paid) {
        return new CreditHistory(Map.of(), Map.of(), 0, 0, new CreditHistory.Recent(Money.of(earned), Money.of(spent),
                Money.of(staked), Money.of(won), Money.of(received), Money.of(paid)));
    }

    private static CreditHistory record(int onTime, int late) {
        return new CreditHistory(Map.of(), Map.of(), onTime, late, CreditHistory.Recent.NONE);
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
    @DisplayName("what was earned lately counts a quarter; what was earned before that does not count at all")
    void earnedLately() {
        assertThat(limit(10_000, lately(80_000, 0, 0, 0, 0, 0))).isEqualTo(Money.of(30_000));
        CreditHistory longAgo = new CreditHistory(Map.of(TransactionKind.SELL, Money.of(1_000_000)), Map.of(), 0, 0,
                CreditHistory.Recent.NONE);
        assertThat(limit(10_000, longAgo)).as("a lifetime of earning, nothing lately").isEqualTo(Money.of(10_000));
    }

    @Test
    @DisplayName("spending most of what came in lately lowers it, down to half")
    void spending() {
        // capacity 10,000 + 10,000 = 20,000; earned 40k of 160k moved → ×0.625
        assertThat(limit(10_000, lately(40_000, 120_000, 0, 0, 0, 0))).isEqualTo(Money.of(12_000));
    }

    @Test
    @DisplayName("winning lately counts like earning; losing lately cuts it, never below a quarter")
    void gambling() {
        assertThat(limit(10_000, lately(0, 0, 10_000, 50_000, 0, 0))).isEqualTo(Money.of(20_000));
        // 8,000 lost against 10,000 balance and nothing earned → ×0.25 (floor)
        assertThat(limit(10_000, lately(0, 0, 9_000, 1_000, 0, 0))).isEqualTo(Money.of(2_500));
        // 2,500 lost against 10,000 → ×0.75
        assertThat(limit(10_000, lately(0, 0, 3_000, 500, 0, 0))).isEqualTo(Money.of(7_500));
    }

    @Test
    @DisplayName("money passed back and forth between friends earns nobody anything")
    void transfersAreNetted() {
        assertThat(limit(10_000, lately(0, 0, 0, 0, 1_000_000, 1_000_000))).isEqualTo(Money.of(10_000));
        assertThat(limit(10_000, lately(0, 0, 0, 0, 40_000, 0))).isEqualTo(Money.of(20_000));
    }

    @Test
    @DisplayName("paying loans back on time raises it, late ones cut it — for life")
    void record() {
        assertThat(limit(10_000, record(5, 0))).isEqualTo(Money.of(15_000));
        assertThat(limit(10_000, record(0, 2))).isEqualTo(Money.of(4_000));
        assertThat(limit(10_000, record(50, 0))).as("at most double").isEqualTo(Money.of(20_000));
        assertThat(limit(10_000, record(0, 9))).as("at least a quarter").isEqualTo(Money.of(2_500));
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
                new CreditHistory(Map.of(), Map.of(), 1, 0, new CreditHistory.Recent(Money.of(4_000), Money.ZERO,
                        Money.of(1_000), Money.ZERO, Money.ZERO, Money.ZERO))), NO_CAP);
        assertThat(limit.earned()).isEqualTo(Money.of(4_000));
        assertThat(limit.gambledAway()).isEqualTo(Money.of(1_000));
        assertThat(limit.capacity()).isEqualTo(Money.of(11_000));
        assertThat(limit.gambling()).isEqualTo(1.0 - 1_000 / 14_000.0);
        assertThat(limit.record()).isEqualTo(1.1);
    }
}
