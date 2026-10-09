package de.raindancer.modules.claims;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.DebtKeeper;
import de.raindancer.core.social.economy.Debts;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.service.UpkeepService;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.UpkeepStore;
import org.bukkit.plugin.Plugin;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UpkeepServiceTest {

    private static final long HOUR = 3_600_000L;
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    @TempDir
    Path folder;

    private final long[] now = {1_000_000_000L};
    private final ClaimRegistry registry = new ClaimRegistry();
    private final Bank bank = new Bank();
    private final Plugin plugin = mock(Plugin.class);

    @BeforeEach
    void economy() {
        Economies.provide(plugin, bank);
    }

    @AfterEach
    void reset() {
        Economies.clear();
        Debts.clear();
    }

    /** A balance book that records every call it gets, with its source. */
    static final class Bank implements Economy {
        final Map<UUID, Long> balances = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override public String name() { return "test"; }
        @Override public Currency currency() { return Currency.DEFAULT; }
        @Override public boolean hasAccount(UUID player) { return true; }
        @Override public Money balance(UUID player) { return Money.of(balances.getOrDefault(player, 0L)); }
        @Override public EconomyResult deposit(UUID player, Money amount, String reason) {
            balances.merge(player, amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }
        @Override public EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (balance(player).minor() < amount.minor()) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            balances.merge(player, -amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }
        @Override public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
            EconomyResult result = withdraw(player, amount, reason);
            calls.add("withdraw " + amount.minor() + " " + source + " " + result.outcome());
            return result;
        }
        @Override public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private ClaimSettings upkeep(String perChunk, double growth, int days) {
        return ClaimSettings.DEFAULTS.withUpkeep(perChunk, growth, 24, days);
    }

    private Claim claim(UUID owner, int x, int chunksWide) {
        ClaimShape shape = ClaimShape.rectangle(x, 0, x + 16 * chunksWide - 1, 15, 0, 128);
        Claim claim = new Claim(UUID.randomUUID(), "c" + x, WORLD, "world", shape, owner);
        registry.add(claim);
        return claim;
    }

    private UpkeepService service(ClaimSettings settings) {
        return new UpkeepService(registry, new UpkeepStore(folder), settings, () -> now[0]);
    }

    @Test
    @DisplayName("off by default: nothing is billed, nothing is owed and no economy is asked")
    void off() {
        claim(OWNER, 0, 2);
        UpkeepService service = service(ClaimSettings.DEFAULTS);
        now[0] += 1000 * HOUR;
        assertThat(service.settle(OWNER).outcome()).isEqualTo(UpkeepService.Outcome.NOT_DUE);
        assertThat(service.owed(OWNER)).isEqualTo(Money.ZERO);
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("the first time an owner is seen they get a full period, not an instant bill")
    void firstSightIsAGrace() {
        claim(OWNER, 0, 1);
        UpkeepService service = service(upkeep("10", 0, 0));
        assertThat(service.settle(OWNER).outcome()).isEqualTo(UpkeepService.Outcome.NOT_DUE);
        assertThat(service.nextDue(OWNER)).hasValue(now[0] + 24 * HOUR);
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("when due the owner is charged for every chunk, with the claims.upkeep source, once")
    void chargedOnce() {
        claim(OWNER, 0, 2);
        bank.balances.put(OWNER, 1_000_000L);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settle(OWNER);
        now[0] += 24 * HOUR;

        UpkeepService.Billing first = service.settle(OWNER);
        UpkeepService.Billing again = service.settle(OWNER);

        assertThat(first.outcome()).isEqualTo(UpkeepService.Outcome.PAID);
        assertThat(first.amount()).isEqualTo(Money.of(2000));
        assertThat(again.outcome()).isEqualTo(UpkeepService.Outcome.NOT_DUE);
        assertThat(bank.calls).containsExactly("withdraw 2000 claims.upkeep DONE");
        assertThat(service.nextDue(OWNER)).hasValue(now[0] + 24 * HOUR);
    }

    @Test
    @DisplayName("chunks are counted across all of an owner's claims, and each further one costs more")
    void progressiveAcrossClaims() {
        claim(OWNER, 0, 1);
        claim(OWNER, 1024, 2);
        UpkeepService service = service(upkeep("10", 10, 0));
        // 3 chunks at 10.00 growing 10%: 1000 + 1100 + 1210
        assertThat(service.billFor(OWNER)).isEqualTo(Money.of(3310));
        assertThat(service.chunksHeld(OWNER)).isEqualTo(3);
    }

    @Test
    @DisplayName("an owner who cannot pay is in arrears: it is recorded, not retried, and Core sees the debt")
    void arrears() {
        claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 0));
        Debts.provide(plugin, service);
        service.settle(OWNER);
        now[0] += 24 * HOUR;

        UpkeepService.Billing missed = service.settle(OWNER);
        service.settle(OWNER);

        assertThat(missed.outcome()).isEqualTo(UpkeepService.Outcome.ARREARS);
        assertThat(service.owed(OWNER)).isEqualTo(Money.of(2000));
        assertThat(Debts.inDebt(OWNER)).isTrue();
        assertThat(Debts.owed(OWNER)).isEqualTo(Money.of(2000));
        assertThat(bank.calls).hasSize(1);
        assertThat(service.account(OWNER).since()).isEqualTo(now[0]);
    }

    @Test
    @DisplayName("income collected by the economy pays the debt down, never below zero")
    void incomePaysTheDebt() {
        claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settle(OWNER);
        now[0] += 24 * HOUR;
        service.settle(OWNER);

        assertThat(service.paid(OWNER, Money.of(500))).isEqualTo(Money.of(500));
        assertThat(service.owed(OWNER)).isEqualTo(Money.of(1500));
        assertThat(service.paid(OWNER, Money.of(9999))).isEqualTo(Money.of(1500));
        assertThat(service.owed(OWNER)).isEqualTo(Money.ZERO);
        assertThat(service.account(OWNER).since()).isZero();
    }

    @Test
    @DisplayName("paying the arrears takes it from the owner, whatever they can afford of it")
    void payingTheArrears() {
        claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settle(OWNER);
        now[0] += 24 * HOUR;
        service.settle(OWNER);

        bank.balances.put(OWNER, 800L);
        UpkeepService.Payment partial = service.pay(OWNER);
        assertThat(partial.paid()).isEqualTo(Money.of(800));
        assertThat(partial.left()).isEqualTo(Money.of(1200));

        bank.balances.put(OWNER, 5000L);
        UpkeepService.Payment rest = service.pay(OWNER);
        assertThat(rest.left()).isEqualTo(Money.ZERO);
        assertThat(bank.balances.get(OWNER)).isEqualTo(3800L);
        assertThat(service.owed(OWNER)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("no economy: nothing is advanced or owed, so a server that has not set one up yet is not billed")
    void noEconomy() {
        claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settle(OWNER);
        long due = service.nextDue(OWNER).orElseThrow();
        Economies.clear();
        now[0] += 24 * HOUR;

        assertThat(service.settle(OWNER).outcome()).isEqualTo(UpkeepService.Outcome.UNAVAILABLE);
        assertThat(service.owed(OWNER)).isEqualTo(Money.ZERO);
        assertThat(service.nextDue(OWNER)).hasValue(due);
    }

    @Test
    @DisplayName("the schedule and the debt survive a restart, so nobody is billed twice or let off")
    void survivesRestart() {
        claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settle(OWNER);
        now[0] += 24 * HOUR;
        service.settle(OWNER);

        UpkeepService restarted = service(upkeep("10", 0, 0));
        assertThat(restarted.owed(OWNER)).isEqualTo(Money.of(2000));
        assertThat(restarted.nextDue(OWNER)).isEqualTo(service.nextDue(OWNER));
        assertThat(restarted.settle(OWNER).outcome()).isEqualTo(UpkeepService.Outcome.NOT_DUE);
        assertThat(bank.calls).hasSize(1);
    }

    @Test
    @DisplayName("claims without an owner (server land) are never billed")
    void serverLandIsFree() {
        claim(null, 0, 3);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settleAll();
        now[0] += 24 * HOUR;
        assertThat(service.settleAll()).isEmpty();
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("with the lift switched off a claim in arrears keeps protecting however long it has been")
    void neverLiftsByDefault() {
        Claim claim = claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settle(OWNER);
        now[0] += 24 * HOUR;
        service.settle(OWNER);
        now[0] += 5000 * 24 * HOUR;
        service.refreshProtection();
        assertThat(claim.lapsed()).isFalse();
    }

    @Test
    @DisplayName("after the configured days in arrears the claim stops protecting, and paying restores it")
    void liftsAndRestores() {
        Claim claim = claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 3));
        service.settle(OWNER);
        now[0] += 24 * HOUR;
        service.settle(OWNER);

        now[0] += 2 * 24 * HOUR;
        service.refreshProtection();
        assertThat(claim.lapsed()).as("two days in").isFalse();

        now[0] += 24 * HOUR;
        service.refreshProtection();
        assertThat(claim.lapsed()).as("three days in").isTrue();

        bank.balances.put(OWNER, 1_000_000L);
        service.pay(OWNER);
        assertThat(claim.lapsed()).isFalse();
    }

    @Test
    @DisplayName("switching upkeep off again stops enforcing and collecting an old debt, without erasing it")
    void switchedOffLater() {
        claim(OWNER, 0, 2);
        UpkeepService service = service(upkeep("10", 0, 0));
        service.settle(OWNER);
        now[0] += 24 * HOUR;
        service.settle(OWNER);

        service.settings(ClaimSettings.DEFAULTS);
        assertThat(service.owed(OWNER)).isEqualTo(Money.ZERO);

        service.settings(upkeep("10", 0, 0));
        assertThat(service.owed(OWNER)).isEqualTo(Money.of(2000));
    }
}
