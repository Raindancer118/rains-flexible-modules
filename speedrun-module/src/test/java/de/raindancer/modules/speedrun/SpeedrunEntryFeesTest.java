package de.raindancer.modules.speedrun;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpeedrunEntryFeesTest {

    @TempDir
    Path folder;

    private final Bank bank = new Bank();
    private final List<String> told = new ArrayList<>();
    private final List<String> logged = new ArrayList<>();
    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();
    private SpeedrunSettings settings = SpeedrunSettings.DEFAULTS.withEconomy("50", 10, "100");
    private SpeedrunEntryLedger ledger;
    private SpeedrunEntryFees fees;

    private static final class Bank implements Economy {
        final Map<UUID, Money> balances = new HashMap<>();
        boolean treasuryEmpty;

        public String name() {
            return "bank";
        }

        public Currency currency() {
            return Currency.DEFAULT;
        }

        public boolean hasAccount(UUID player) {
            return balances.containsKey(player);
        }

        public boolean createAccount(UUID player) {
            balances.putIfAbsent(player, Money.ZERO);
            return true;
        }

        public Money balance(UUID player) {
            return balances.getOrDefault(player, Money.ZERO);
        }

        public EconomyResult deposit(UUID player, Money amount, String reason) {
            if (treasuryEmpty) {
                return EconomyResult.failed(EconomyResult.Outcome.TREASURY_EMPTY, amount, balance(player));
            }
            balances.merge(player, amount, Money::plus);
            return EconomyResult.done(amount, balances.get(player));
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (balance(player).isAtLeast(amount)) {
                balances.merge(player, amount.negate(), Money::plus);
                return EconomyResult.done(amount, balances.get(player));
            }
            return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
        }

        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            withdraw(from, amount, reason);
            return deposit(to, amount, reason);
        }
    }

    @BeforeEach
    void start() {
        bank.balances.put(ana, Money.of(10_000));
        bank.balances.put(bo, Money.of(10_000));
        bank.balances.put(cy, Money.of(150));
        Economies.provide(mock(org.bukkit.plugin.Plugin.class), bank);
        build();
    }

    private void build() {
        ledger = new SpeedrunEntryLedger(folder.resolve("entries.yml"));
        ledger.load();
        fees = new SpeedrunEntryFees(ledger, (player, key, pairs) -> told.add(player + ":" + key), logged::add,
                () -> settings);
    }

    @AfterEach
    void stop() {
        Economies.clear();
    }

    @Test
    @DisplayName("fee 0 (the default) takes nothing, needs no economy and writes no file")
    void freeIsUntouched() {
        Economies.clear();
        settings = SpeedrunSettings.DEFAULTS;
        assertThat(fees.active()).isFalse();
        assertThat(fees.unaffordable(Set.of(ana, bo))).isEmpty();
        assertThat(fees.charge(Set.of(ana, bo))).isEmpty();
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        assertThat(folder.resolve("entries.yml")).doesNotExist();
    }

    @Test
    @DisplayName("charging takes the fee from every racer and puts it in the pot")
    void charges() {
        assertThat(fees.charge(Set.of(ana, bo))).isEmpty();
        assertThat(bank.balance(ana)).isEqualTo(Money.of(5_000));
        assertThat(ledger.pot()).isEqualTo(Money.of(10_000));
        assertThat(fees.charge(Set.of(ana))).as("never twice for the same run").isEmpty();
        assertThat(bank.balance(ana)).isEqualTo(Money.of(5_000));
    }

    @Test
    @DisplayName("somebody who cannot pay is named and told, and a failed charge leaves nobody out of pocket")
    void rollsBack() {
        assertThat(fees.unaffordable(Set.of(ana, cy))).containsExactly(cy);
        assertThat(told).contains(cy + ":speedrun.entry.cannot-pay");
        assertThat(fees.charge(Set.of(ana, cy))).isPresent();
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000));
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a fee with no economy refuses everybody rather than letting them race free")
    void noEconomy() {
        Economies.clear();
        assertThat(fees.unaffordable(Set.of(ana))).containsExactly(ana);
        assertThat(fees.charge(Set.of(ana))).isPresent();
    }

    @Test
    @DisplayName("refunding gives every fee back and empties the pot")
    void refunds() {
        fees.charge(Set.of(ana, bo));
        fees.refundAll("the run was cancelled");
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(10_000));
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        assertThat(told).contains(ana + ":speedrun.entry.refunded");
    }

    @Test
    @DisplayName("the winners share the pot after the house cut, and the pot is empty afterwards")
    void pays() {
        fees.charge(Set.of(ana, bo));
        fees.payOut(List.of(List.of(ana)));
        assertThat(bank.balance(ana)).isEqualTo(Money.of(5_000 + 9_000));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(5_000));
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        assertThat(told).contains(ana + ":speedrun.entry.prize");
    }

    @Test
    @DisplayName("a payout the treasury refuses is told and logged, and nothing crashes")
    void treasuryEmpty() {
        fees.charge(Set.of(ana, bo));
        bank.treasuryEmpty = true;
        fees.payOut(List.of(List.of(ana)));
        assertThat(told).contains(ana + ":speedrun.entry.prize-refused");
        assertThat(logged).anyMatch(line -> line.contains(ana.toString()));
    }

    @Test
    @DisplayName("a pot left behind by a restart is read back and refunded")
    void restart() {
        fees.charge(Set.of(ana, bo));
        build();
        assertThat(ledger.pot()).isEqualTo(Money.of(10_000));
        fees.refundAll("the server restarted mid-run");
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(10_000));
    }
}
