package de.raindancer.modules.tpa.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.tpa.TpaSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TpaFeesTest {

    private final UUID who = UUID.randomUUID();
    private final Bank bank = new Bank();
    private final TpaFees fees = new TpaFees(TpaSettings.DEFAULTS);

    private static final class Bank implements Economy {
        final Map<UUID, Money> balances = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        public String name() {
            return "bank";
        }

        public Currency currency() {
            return Currency.DEFAULT;
        }

        public boolean hasAccount(UUID player) {
            return true;
        }

        public Money balance(UUID player) {
            return balances.getOrDefault(player, Money.ZERO);
        }

        public EconomyResult deposit(UUID player, Money amount, String reason) {
            balances.merge(player, amount, Money::plus);
            return EconomyResult.done(amount, balance(player));
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return withdraw(player, amount, reason, "?");
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
            calls.add("withdraw " + amount.minor() + " " + source);
            if (!balance(player).isAtLeast(amount)) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            balances.merge(player, amount.negate(), Money::plus);
            return EconomyResult.done(amount, balance(player));
        }

        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    @AfterEach
    void reset() {
        Economies.clear();
    }

    private void withBank(long balance) {
        bank.balances.put(who, Money.of(balance));
        Economies.provide(mock(org.bukkit.plugin.Plugin.class), bank);
    }

    @Test
    @DisplayName("every price is off by default, and needs no economy")
    void offByDefault() {
        TpaSettings d = TpaSettings.DEFAULTS;
        assertThat(java.util.List.of(d.price(), d.pricePer100Blocks(), d.crossWorldPrice(),
                d.backPrice(), d.skipCooldownPrice())).containsOnly("0");
        assertThat(fees.trip(true, 500)).isEqualTo(Money.ZERO);
        assertThat(fees.back()).isEqualTo(Money.ZERO);
        assertThat(fees.skip()).isEqualTo(Money.ZERO);
        assertThat(fees.charge(who, Money.ZERO, TpaFees.TRIP, Money.ZERO).paid()).isTrue();
    }

    @Test
    @DisplayName("prices come from the settings and follow a reload")
    void readsSettings() {
        fees.settings(TpaSettings.DEFAULTS.withPrice("5").withPricePer100Blocks("1")
                .withCrossWorldPrice("20").withBackPrice("3").withSkipCooldownPrice("7"));
        assertThat(fees.trip(true, 200)).isEqualTo(Money.of(700));
        assertThat(fees.trip(false, Double.NaN)).isEqualTo(Money.of(2500));
        assertThat(fees.back()).isEqualTo(Money.of(300));
        assertThat(fees.skip()).isEqualTo(Money.of(700));
    }

    @Test
    @DisplayName("a real price with no economy refuses")
    void noEconomy() {
        assertThat(fees.charge(who, Money.of(100), TpaFees.TRIP, Money.ZERO).paid()).isFalse();
    }

    @Test
    @DisplayName("the trip and the skip are taken under their own sources, back under its own")
    void sources() {
        withBank(1_000);
        assertThat(fees.charge(who, Money.of(100), TpaFees.TRIP, Money.of(40)).paid()).isTrue();
        assertThat(fees.charge(who, Money.of(10), TpaFees.BACK, Money.ZERO).paid()).isTrue();
        assertThat(bank.calls).containsExactly("withdraw 100 tpa.fee", "withdraw 40 tpa.skip-cooldown",
                "withdraw 10 tpa.back");
    }

    @Test
    @DisplayName("when the skip cannot be paid the trip's price is given back too")
    void secondFailsFirstRefunded() {
        withBank(120);
        TpaFees.Charge charge = fees.charge(who, Money.of(100), TpaFees.TRIP, Money.of(50));
        assertThat(charge.paid()).isFalse();
        assertThat(bank.balance(who)).isEqualTo(Money.of(120));
    }

    @Test
    @DisplayName("somebody who quits mid-wait gets their money back, once")
    void quittingRefunds() {
        withBank(1_000);
        TpaFees.Charge charge = fees.charge(who, Money.of(100), TpaFees.TRIP, Money.of(40));
        fees.hold(who, charge.taken());
        assertThat(bank.balance(who)).isEqualTo(Money.of(860));

        fees.refundHeld(who);
        fees.refundHeld(who);

        assertThat(bank.balance(who)).isEqualTo(Money.of(1_000));
    }

    @Test
    @DisplayName("what is settled on arrival is no longer held, so quitting later refunds nothing")
    void settledIsNotRefunded() {
        withBank(1_000);
        TpaFees.Charge charge = fees.charge(who, Money.of(100), TpaFees.TRIP, Money.ZERO);
        fees.hold(who, charge.taken());

        assertThat(fees.settle(who)).isEqualTo(charge.taken());
        fees.refundHeld(who);

        assertThat(bank.balance(who)).isEqualTo(Money.of(900));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("one paid trip at a time: a second hold is refused, so the first trip's cancellation can never refund it")
    void oneTripAtATime() {
        java.util.UUID who = java.util.UUID.randomUUID();
        TpaFees fees = new TpaFees(de.raindancer.modules.tpa.TpaSettings.DEFAULTS);
        TpaFees.Taken first = new TpaFees.Taken(de.raindancer.core.social.economy.Money.of(100), TpaFees.TRIP,
                de.raindancer.core.social.economy.Money.ZERO);
        TpaFees.Taken second = new TpaFees.Taken(de.raindancer.core.social.economy.Money.of(50), TpaFees.TRIP,
                de.raindancer.core.social.economy.Money.ZERO);
        org.assertj.core.api.Assertions.assertThat(fees.hold(who, first)).isTrue();
        org.assertj.core.api.Assertions.assertThat(fees.holding(who)).isTrue();
        org.assertj.core.api.Assertions.assertThat(fees.hold(who, second)).isFalse();
        org.assertj.core.api.Assertions.assertThat(fees.settle(who)).isEqualTo(first);
    }
}
