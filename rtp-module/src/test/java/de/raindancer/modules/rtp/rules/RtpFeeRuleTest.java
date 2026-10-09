package de.raindancer.modules.rtp.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.rtp.rules.RtpFeeRule.AtCooldown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RtpFeeRuleTest {

    private final RtpFeeRule rule = new RtpFeeRule();
    private final Money skip = Money.of(500);

    @Test
    @DisplayName("nothing is waiting: go, and never charge the skip price")
    void ready() {
        assertThat(rule.atCooldown(true, false, skip, true)).isEqualTo(AtCooldown.GO);
    }

    @Test
    @DisplayName("somebody who bypasses the wait goes free")
    void bypass() {
        assertThat(rule.atCooldown(false, true, skip, true)).isEqualTo(AtCooldown.GO);
    }

    @Test
    @DisplayName("waiting with no skip price is a plain refusal, however much they ask")
    void noPrice() {
        assertThat(rule.atCooldown(false, false, Money.ZERO, true)).isEqualTo(AtCooldown.REFUSE);
    }

    @Test
    @DisplayName("waiting with a skip price offers it first")
    void offered() {
        assertThat(rule.atCooldown(false, false, skip, false)).isEqualTo(AtCooldown.OFFER);
    }

    @Test
    @DisplayName("having accepted the offer, they go and pay")
    void paying() {
        assertThat(rule.atCooldown(false, false, skip, true)).isEqualTo(AtCooldown.GO_PAYING);
    }
}
