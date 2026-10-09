package de.raindancer.modules.rtp.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.rtp.RtpSettings;
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

class RtpFeesTest {

    private final UUID who = UUID.randomUUID();
    private final Bank bank = new Bank();
    private final RtpFees fees = new RtpFees(RtpSettings.DEFAULTS);

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
    @DisplayName("prices are read from the settings as written, and follow a reload")
    void readsSettings() {
        assertThat(fees.fee()).isEqualTo(Money.ZERO);
        fees.settings(RtpSettings.DEFAULTS.withPrice("2.50").withSkipCooldownPrice("10"));
        assertThat(fees.fee()).isEqualTo(Money.of(250));
        assertThat(fees.skip()).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("a free trip needs no economy at all")
    void free() {
        RtpFees.Charge charge = fees.charge(who, Money.ZERO, Money.ZERO);
        assertThat(charge.paid()).isTrue();
        assertThat(charge.taken().total()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a real price with no economy refuses, never lets the trip through free")
    void noEconomy() {
        assertThat(fees.charge(who, Money.of(100), Money.ZERO).paid()).isFalse();
    }

    @Test
    @DisplayName("the fee and the skip are taken under their own source keys")
    void sources() {
        withBank(1_000);
        RtpFees.Charge charge = fees.charge(who, Money.of(100), Money.of(50));
        assertThat(charge.paid()).isTrue();
        assertThat(bank.calls).containsExactly("withdraw 100 rtp.fee", "withdraw 50 rtp.skip-cooldown");
        assertThat(bank.balance(who)).isEqualTo(Money.of(850));
    }

    @Test
    @DisplayName("when the skip cannot be paid, the fee already taken is given back")
    void secondFailsFirstRefunded() {
        withBank(120);
        RtpFees.Charge charge = fees.charge(who, Money.of(100), Money.of(50));
        assertThat(charge.paid()).isFalse();
        assertThat(charge.refusal().outcome()).isEqualTo(EconomyResult.Outcome.NOT_ENOUGH);
        assertThat(bank.balance(who)).as("nothing stays taken").isEqualTo(Money.of(120));
    }

    @Test
    @DisplayName("a refund gives back exactly what was taken")
    void refund() {
        withBank(1_000);
        RtpFees.Charge charge = fees.charge(who, Money.of(100), Money.of(50));
        fees.refund(who, charge.taken());
        assertThat(bank.balance(who)).isEqualTo(Money.of(1_000));
    }
}
