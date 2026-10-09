package de.raindancer.modules.moderation;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.rules.BuyoffRule;
import de.raindancer.modules.moderation.rules.FineRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** What a fine comes to, who may give how much of one, and what ending a mute costs. */
class FineRuleTest {

    private final FineRule rule = new FineRule();
    private final BuyoffRule buyoff = new BuyoffRule();

    private static Money m(long minor) {
        return Money.of(minor);
    }

    private static Money parse(String text) {
        try {
            return Money.of(Long.parseLong(text));
        } catch (NumberFormatException notAnAmount) {
            return Money.ZERO;
        }
    }

    @Test
    @DisplayName("the ladder is read in order, and what is not an amount is skipped")
    void ladder() {
        assertThat(FineRule.ladder("50, 100, x, 0, 250", FineRuleTest::parse)).containsExactly(m(50), m(100), m(250));
        assertThat(FineRule.ladder("", FineRuleTest::parse)).isEmpty();
        assertThat(FineRule.ladder(null, FineRuleTest::parse)).isEmpty();
    }

    @Test
    @DisplayName("each warning in the window costs the next rung, and the last rung repeats")
    void escalates() {
        List<Money> ladder = List.of(m(50), m(100), m(250));
        assertThat(rule.warnFine(1, m(10_000), ladder, 0, Money.ZERO, Money.ZERO)).isEqualTo(m(50));
        assertThat(rule.warnFine(2, m(10_000), ladder, 0, Money.ZERO, Money.ZERO)).isEqualTo(m(100));
        assertThat(rule.warnFine(3, m(10_000), ladder, 0, Money.ZERO, Money.ZERO)).isEqualTo(m(250));
        assertThat(rule.warnFine(9, m(10_000), ladder, 0, Money.ZERO, Money.ZERO)).isEqualTo(m(250));
    }

    @Test
    @DisplayName("with nothing set a warning costs nothing")
    void off() {
        assertThat(rule.warnFine(1, m(10_000), List.of(), 0, Money.ZERO, Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a share of the balance is held between the least and the most")
    void percentIsBounded() {
        assertThat(rule.warnFine(1, m(1_000), List.of(), 10, Money.ZERO, Money.ZERO)).isEqualTo(m(100));
        assertThat(rule.warnFine(1, m(1_000), List.of(), 10, m(150), Money.ZERO)).isEqualTo(m(150));
        assertThat(rule.warnFine(1, m(1_000), List.of(), 10, Money.ZERO, m(60))).isEqualTo(m(60));
        assertThat(rule.warnFine(1, Money.ZERO, List.of(), 10, m(20), Money.ZERO)).as("a broke player still owes the least").isEqualTo(m(20));
    }

    @Test
    @DisplayName("with both set, the larger one is charged")
    void largerOfBoth() {
        assertThat(rule.warnFine(1, m(1_000), List.of(m(50)), 10, Money.ZERO, Money.ZERO)).isEqualTo(m(100));
        assertThat(rule.warnFine(1, m(100), List.of(m(50)), 10, Money.ZERO, Money.ZERO)).isEqualTo(m(50));
    }

    @Test
    @DisplayName("a moderator may fine up to the limit, an admin any amount, and zero is no limit")
    void limit() {
        assertThat(rule.mayFine(false, m(500), m(500), "500").isAllowed()).isTrue();
        var refused = rule.mayFine(false, m(501), m(500), "500");
        assertThat(refused.isRefused()).isTrue();
        assertThat(refused.reason()).isEqualTo(FineRule.TOO_MUCH);
        assertThat(refused.detail()).isEqualTo("500");
        assertThat(rule.mayFine(true, m(9_999), m(500), "500").isAllowed()).isTrue();
        assertThat(rule.mayFine(false, m(9_999), Money.ZERO, "0").isAllowed()).isTrue();
    }

    @Test
    @DisplayName("the victim's share is rounded down and the rest stays the server's")
    void victimShare() {
        FineRule.Split half = rule.split(m(1_001), 50);
        assertThat(half.victim()).isEqualTo(m(500));
        assertThat(half.server()).isEqualTo(m(501));
        assertThat(rule.split(m(1_000), 0).victim()).isEqualTo(Money.ZERO);
        assertThat(rule.split(m(1_000), 0).server()).isEqualTo(m(1_000));
        assertThat(rule.split(m(1_000), 100).server()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a mute is paid for per started hour left")
    void buyoffPrice() {
        assertThat(buyoff.price(m(100), Duration.ofMinutes(1))).isEqualTo(m(100));
        assertThat(buyoff.price(m(100), Duration.ofMinutes(60))).isEqualTo(m(100));
        assertThat(buyoff.price(m(100), Duration.ofMinutes(61))).isEqualTo(m(200));
        assertThat(buyoff.price(m(100), Duration.ofHours(3).plusSeconds(1))).isEqualTo(m(400));
        assertThat(buyoff.price(m(100), Duration.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("only a temporary mute no longer than the limit may be bought off, and only when priced")
    void buyoffEligibility() {
        Optional<Duration> left = Optional.of(Duration.ofHours(2));
        assertThat(buyoff.mayBuyOff(m(100), 24, true, Duration.ofHours(24), left).isAllowed()).isTrue();
        assertThat(buyoff.mayBuyOff(Money.ZERO, 24, true, Duration.ofHours(2), left).reason()).isEqualTo(BuyoffRule.OFF);
        assertThat(buyoff.mayBuyOff(m(100), 24, false, null, Optional.empty()).reason()).isEqualTo(BuyoffRule.NOT_MUTED);
        assertThat(buyoff.mayBuyOff(m(100), 24, true, null, Optional.empty()).reason()).isEqualTo(BuyoffRule.PERMANENT);
        assertThat(buyoff.mayBuyOff(m(100), 24, true, Duration.ofHours(25), left).reason()).isEqualTo(BuyoffRule.TOO_LONG);
    }
}
