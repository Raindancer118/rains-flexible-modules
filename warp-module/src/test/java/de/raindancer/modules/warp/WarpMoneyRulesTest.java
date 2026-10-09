package de.raindancer.modules.warp;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.warp.rules.WarpFeeRule;
import de.raindancer.modules.warp.rules.WarpRentRule;
import de.raindancer.modules.warp.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WarpMoneyRulesTest {

    @Nested
    @DisplayName("visit fees")
    class Fees {

        private final WarpFeeRule rule = new WarpFeeRule();

        @Test
        @DisplayName("with a cap of zero owners may not charge at all")
        void capZero() {
            assertThat(rule.visitFee(Money.of(500), Money.ZERO)).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("a fee above the cap is read as the cap, so lowering the cap takes effect at once")
        void cappedAtRead() {
            assertThat(rule.visitFee(Money.of(500), Money.of(300))).isEqualTo(Money.of(300));
            assertThat(rule.visitFee(Money.of(200), Money.of(300))).isEqualTo(Money.of(200));
        }

        @Test
        @DisplayName("nothing set is nothing to pay, and a negative stored value is nothing too")
        void nothingSet() {
            assertThat(rule.visitFee(Money.ZERO, Money.of(300))).isEqualTo(Money.ZERO);
            assertThat(rule.visitFee(Money.of(-5), Money.of(300))).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("the server's cut is a percentage of the fee, rounded down")
        void cut() {
            assertThat(rule.cut(Money.of(1000), 10)).isEqualTo(Money.of(100));
            assertThat(rule.cut(Money.of(999), 10)).isEqualTo(Money.of(99));
            assertThat(rule.cut(Money.of(100), 29)).as("whole numbers, not a double").isEqualTo(Money.of(29));
            assertThat(rule.cut(Money.of(1000), 0)).isEqualTo(Money.ZERO);
            assertThat(rule.cut(Money.of(1000), 100)).isEqualTo(Money.of(1000));
            assertThat(rule.cut(Money.of(1000), 250)).as("clamped to the whole fee").isEqualTo(Money.of(1000));
            assertThat(rule.cut(Money.of(1000), -3)).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("the owner and anybody with the bypass do not pay; a visitor does")
        void whoPays() {
            UUID owner = UUID.randomUUID();
            UUID visitor = UUID.randomUUID();
            assertThat(rule.pays(visitor, owner, false)).isTrue();
            assertThat(rule.pays(owner, owner, false)).isFalse();
            assertThat(rule.pays(visitor, owner, true)).isFalse();
            assertThat(rule.pays(visitor, null, false)).as("a warp nobody owns has nobody to pay").isFalse();
        }

        @Test
        @DisplayName("staff who manage warps, and holders of the bypass node, skip every warp fee")
        void bypass() {
            assertThat(rule.bypasses(Set.of(PermissionNodes.MANAGE)::contains)).isTrue();
            assertThat(rule.bypasses(Set.of(PermissionNodes.BYPASS_FEES)::contains)).isTrue();
            assertThat(rule.bypasses(Set.of(PermissionNodes.USE)::contains)).isFalse();
        }
    }

    @Nested
    @DisplayName("rent")
    class Rent {

        private final WarpRentRule rule = new WarpRentRule();
        private static final long WEEK = 7L * 24 * 3600 * 1000;

        @Test
        @DisplayName("a week is a week")
        void week() {
            assertThat(WarpRentRule.WEEK_MILLIS).isEqualTo(WEEK);
        }

        @Test
        @DisplayName("rent is due from the moment the paid time is reached")
        void due() {
            assertThat(rule.isDue(1000, 999)).isFalse();
            assertThat(rule.isDue(1000, 1000)).isTrue();
            assertThat(rule.isDue(1000, 5000)).isTrue();
        }

        @Test
        @DisplayName("paying on time extends from the old date, so a week is never lost or gained")
        void extendsFromTheOldDate() {
            assertThat(rule.extended(10 * WEEK, 10 * WEEK + 5)).isEqualTo(11 * WEEK);
        }

        @Test
        @DisplayName("paying long after the date starts a week from now, with no back-rent")
        void noBackRent() {
            assertThat(rule.extended(WEEK, 20 * WEEK)).isEqualTo(21 * WEEK);
        }

        @Test
        @DisplayName("only a player's own warp is enrolled, and only when rent is on and they do not bypass")
        void enrolment() {
            assertThat(rule.enrols(Money.of(100), true, false)).isTrue();
            assertThat(rule.enrols(Money.ZERO, true, false)).isFalse();
            assertThat(rule.enrols(Money.of(100), false, false)).isFalse();
            assertThat(rule.enrols(Money.of(100), true, true)).isFalse();
        }

        @Test
        @DisplayName("a warp is closed only while rent is on: switching rent off opens everything")
        void closedOnlyWhileRentIsOn() {
            assertThat(rule.isClosed(true, Money.of(100))).isTrue();
            assertThat(rule.isClosed(true, Money.ZERO)).isFalse();
            assertThat(rule.isClosed(false, Money.of(100))).isFalse();
        }
    }
}
