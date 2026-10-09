package de.raindancer.modules.farmworld.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.farmworld.FarmWorldSettings;
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

class FarmFeesTest {

    private final UUID who = UUID.randomUUID();
    private final Bank bank = new Bank();
    private final FarmFees fees = new FarmFees(FarmWorldSettings.DEFAULTS);

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
    @DisplayName("prices are read as written and follow a reload; free needs no economy")
    void readsSettings() {
        assertThat(fees.entry()).isEqualTo(Money.ZERO);
        assertThat(fees.pass()).isEqualTo(Money.ZERO);
        assertThat(fees.charge(who, Money.ZERO, FarmFees.ENTRY).paid()).isTrue();

        fees.settings(FarmWorldSettings.DEFAULTS.withEntryPrice("2.50").withDayPassPrice("10"));
        assertThat(fees.entry()).isEqualTo(Money.of(250));
        assertThat(fees.pass()).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("a real price with no economy refuses")
    void noEconomy() {
        assertThat(fees.charge(who, Money.of(100), FarmFees.ENTRY).paid()).isFalse();
    }

    @Test
    @DisplayName("an entry and a pass are taken under their own sources")
    void sources() {
        withBank(10_000);
        fees.charge(who, Money.of(100), FarmFees.ENTRY);
        fees.charge(who, Money.of(900), FarmFees.PASS);
        assertThat(bank.calls).containsExactly("withdraw 100 farmworld.entry", "withdraw 900 farmworld.pass");
    }

    @Test
    @DisplayName("not enough money refuses and takes nothing")
    void notEnough() {
        withBank(50);
        FarmFees.Charge charge = fees.charge(who, Money.of(100), FarmFees.ENTRY);
        assertThat(charge.paid()).isFalse();
        assertThat(charge.refusal().outcome()).isEqualTo(EconomyResult.Outcome.NOT_ENOUGH);
        assertThat(bank.balance(who)).isEqualTo(Money.of(50));
    }

    @Test
    @DisplayName("a refund gives back what was taken, from whichever source")
    void refund() {
        withBank(1_000);
        FarmFees.Charge charge = fees.charge(who, Money.of(300), FarmFees.PASS);
        fees.refund(who, charge.taken());
        assertThat(bank.balance(who)).isEqualTo(Money.of(1_000));
    }

    @Test
    @DisplayName("somebody who quits mid-wait gets their money back, once")
    void quittingRefunds() {
        withBank(1_000);
        fees.hold(who, fees.charge(who, Money.of(300), FarmFees.ENTRY).taken());
        fees.refundHeld(who);
        fees.refundHeld(who);
        assertThat(bank.balance(who)).isEqualTo(Money.of(1_000));
    }

    @Test
    @DisplayName("a settled trip keeps its money, and says whether it was a pass")
    void settled() {
        withBank(1_000);
        fees.hold(who, fees.charge(who, Money.of(300), FarmFees.PASS).taken());
        FarmFees.Taken taken = fees.settle(who);
        assertThat(taken.isPass()).isTrue();
        fees.refundHeld(who);
        assertThat(bank.balance(who)).isEqualTo(Money.of(700));
    }
}
