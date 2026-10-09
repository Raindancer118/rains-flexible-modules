package de.raindancer.modules.hungergames.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.InMemorySessionStore;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.AllGameEvents;
import de.raindancer.modules.hungergames.store.EntryLedger;
import de.raindancer.modules.hungergames.store.GameSession;
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
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EntryFeeServiceTest {

    @TempDir
    Path folder;

    private final Bank bank = new Bank();
    private final List<String> told = new ArrayList<>();
    private final List<String> logged = new ArrayList<>();
    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();
    private AllGameEvents events;
    private GameSession session;
    private EntryLedger ledger;
    private EntryFeeService fees;

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
            if (balance(player).isMoreThan(amount) || balance(player).equals(amount)) {
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
        build(HungerGamesSettings.DEFAULTS.withEconomy("50", 10, "100"));
    }

    private void build(HungerGamesSettings settings) {
        events = new AllGameEvents();
        session = new GameSession(TeamRules::defaults, events, new InMemorySessionStore(), () -> 1_000_000L,
                new Random(7));
        ledger = new EntryLedger(folder.resolve("entries.yml"));
        ledger.load();
        fees = new EntryFeeService(ledger, session, (player, key, pairs) -> told.add(player + ":" + key),
                logged::add, settings);
        session.gate(fees);
        events.also(fees);
    }

    @AfterEach
    void stop() {
        Economies.clear();
    }

    private void toRunning() {
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
    }

    private Money fee() {
        return Money.of(5_000);
    }

    @Test
    @DisplayName("fee 0 (the default) takes nothing, needs no economy and writes no file")
    void freeIsUntouched() {
        Economies.clear();
        build(HungerGamesSettings.DEFAULTS);
        assertThat(session.register(ana, "Anna").outcome()).isEqualTo(GameSession.Outcome.ADDED);
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        assertThat(folder.resolve("entries.yml")).doesNotExist();
    }

    @Test
    @DisplayName("registering charges the fee and puts it in the pot")
    void charges() {
        assertThat(session.register(ana, "Anna").outcome()).isEqualTo(GameSession.Outcome.ADDED);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000).minus(fee()));
        assertThat(ledger.pot()).isEqualTo(fee());
        assertThat(session.register(ana, "Anna").outcome()).as("twice is not twice charged")
                .isEqualTo(GameSession.Outcome.ALREADY);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000).minus(fee()));
    }

    @Test
    @DisplayName("somebody who cannot pay is refused with a reason and is not registered")
    void refuses() {
        GameSession.Registration result = session.register(cy, "Cy");
        assertThat(result.outcome()).isEqualTo(GameSession.Outcome.REFUSED);
        assertThat(result.reason()).isNotBlank();
        assertThat(session.isWhitelisted(cy)).isFalse();
        assertThat(bank.balance(cy)).isEqualTo(Money.of(150));
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a fee with no economy refuses rather than letting anybody in free")
    void noEconomy() {
        Economies.clear();
        assertThat(session.register(ana, "Anna").outcome()).isEqualTo(GameSession.Outcome.REFUSED);
        assertThat(session.isWhitelisted(ana)).isFalse();
    }

    @Test
    @DisplayName("leaving before the round starts gives the fee back; after it started it stays in the pot")
    void leaving() {
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        session.whitelistRemove(ana);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000));
        assertThat(ledger.pot()).isEqualTo(fee());
        toRunning();
        session.whitelistRemove(bo);
        assertThat(bank.balance(bo)).isEqualTo(Money.of(10_000).minus(fee()));
        assertThat(ledger.pot()).isEqualTo(fee());
    }

    @Test
    @DisplayName("a cancelled round gives everybody their fee back")
    void cancelled() {
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        toRunning();
        session.reset();
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(10_000));
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("the winner is paid the pot minus the house cut, and the pot is empty afterwards")
    void winnerPaid() {
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        toRunning();
        session.eliminate(bo, ana);
        // pot 10,000 minus 10% = 9,000; Ana put in 5,000 and gets 9,000
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000 - 5_000 + 9_000));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(10_000 - 5_000));
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        assertThat(told).contains(ana + ":hungergames.prize-won");
    }

    @Test
    @DisplayName("prize split by place: second place is paid too")
    void places() {
        build(HungerGamesSettings.DEFAULTS.withEconomy("50", 0, "70,30"));
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        toRunning();
        session.eliminate(bo, ana);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(5_000 + 7_000));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(5_000 + 3_000));
    }

    @Test
    @DisplayName("a round nobody won gives the fees back, house cut included")
    void nobodyWon() {
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        toRunning();
        session.declareTimeout();
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(10_000));
    }

    @Test
    @DisplayName("a payout the treasury refuses is told and logged, and nothing crashes")
    void treasuryEmpty() {
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        toRunning();
        bank.treasuryEmpty = true;
        session.eliminate(bo, ana);
        assertThat(told).contains(ana + ":hungergames.prize-refused");
        assertThat(logged).anyMatch(line -> line.contains(ana.toString()));
    }

    @Test
    @DisplayName("a restart mid-round keeps the pot: the ledger is read back from its file")
    void restart() {
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        EntryLedger again = new EntryLedger(folder.resolve("entries.yml"));
        again.load();
        assertThat(again.pot()).isEqualTo(fee().plus(fee()));
        assertThat(again.entries()).containsKeys(ana, bo);
    }

    @Test
    @DisplayName("somebody added by name before they ever joined pays when they first join; if they cannot, they are out")
    void placeholder() {
        UUID placeholder = AccountNames.derivedId("Newbie");
        assertThat(session.register(placeholder, "Newbie").outcome()).isEqualTo(GameSession.Outcome.ADDED);
        assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        session.claimRealIdentity(placeholder, ana, "Newbie");
        assertThat(ledger.entries()).containsKey(ana);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(10_000).minus(fee()));

        UUID poor = AccountNames.derivedId("Poor");
        session.register(poor, "Poor");
        session.claimRealIdentity(poor, cy, "Poor");
        assertThat(session.isWhitelisted(cy)).isFalse();
        assertThat(told).contains(cy + ":hungergames.entry-refused-joined");
    }

    @Test
    @DisplayName("the next round with the same tributes charges them again")
    void nextRound() {
        session.register(ana, "Anna");
        session.register(bo, "Bo");
        toRunning();
        session.eliminate(bo, ana);
        session.resetForNextRound();
        assertThat(ledger.entries()).containsKeys(ana, bo);
        assertThat(ledger.pot()).isEqualTo(fee().plus(fee()));
    }
}
