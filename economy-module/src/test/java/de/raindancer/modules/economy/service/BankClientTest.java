package de.raindancer.modules.economy.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkRoles;
import de.raindancer.core.social.economy.BankRequests;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.NetworkEconomy;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.EconomyDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Two "servers" on one bus and one core database: "smp" keeps the money, "lobby" only asks for it. */
@Tag("database")
class BankClientTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private final List<NetworkCalls> calls = new ArrayList<>();
    private NetworkRoles smpRoles;
    private NetworkRoles lobbyRoles;
    private NetworkCalls smpCalls;
    private NetworkCalls lobbyCalls;
    private BankClient lobby;
    private BankClient smp;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Economy bank = new Economy() {
        @Override
        public String name() {
            return "bank";
        }

        @Override
        public Currency currency() {
            return Currency.DEFAULT;
        }

        @Override
        public boolean hasAccount(UUID player) {
            return balances.containsKey(player);
        }

        @Override
        public Money balance(UUID player) {
            return Money.of(balances.getOrDefault(player, 0L));
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            return new EconomyResult(EconomyResult.Outcome.DONE, amount,
                    Money.of(balances.merge(player, amount.minor(), Long::sum)));
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, Money.ZERO);
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, Money.ZERO);
        }
    };

    private Database core() {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return database;
    }

    private NetworkCalls callsOf(String server) {
        NetworkCalls made = new NetworkCalls(hub.bus(server));
        calls.add(made);
        return made;
    }

    @BeforeEach
    void servers() {
        smpRoles = new NetworkRoles(core(), "smp", System::currentTimeMillis);
        lobbyRoles = new NetworkRoles(core(), "lobby", System::currentTimeMillis);
        smpCalls = callsOf("smp");
        lobbyCalls = callsOf("lobby");
        lobby = new BankClient(lobbyCalls, lobbyRoles, "lobby", id -> id.equals("smp") ? "Survival" : id);
        smp = new BankClient(smpCalls, smpRoles, "smp", id -> id.equals("smp") ? "Survival" : id);
    }

    @AfterEach
    void close() {
        calls.forEach(NetworkCalls::close);
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("with no bank anywhere a server is not a client; once one is chosen, every other server is")
    void knowsWhoKeepsTheMoney() {
        lobby.refresh();
        smp.refresh();
        assertThat(lobby.elsewhere()).isFalse();

        assertThat(smpRoles.claimHome(NetworkEconomy.ROLE)).isTrue();
        lobby.refresh();
        smp.refresh();

        assertThat(lobby.elsewhere()).isTrue();
        assertThat(lobby.bankName()).isEqualTo("Survival");
        assertThat(smp.elsewhere()).isFalse();
        assertThat(smp.keepsIt()).isTrue();
    }

    @Test
    @DisplayName("a bank that is down for a moment is still the bank: the lease lapsing does not make this one a bank")
    void aBankThatIsDownStaysTheBank() {
        smpRoles.moveHome(NetworkEconomy.ROLE, "smp");

        lobby.refresh();

        assertThat(lobby.elsewhere()).isTrue();
        assertThat(lobby.reachable()).isFalse();
    }

    @Test
    @DisplayName("moving the bank turns a client into the bank and the bank into a client")
    void followsAMovedBank() {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        lobby.refresh();
        smp.refresh();

        smpRoles.moveHome(NetworkEconomy.ROLE, "lobby");
        lobby.refresh();
        smp.refresh();

        assertThat(lobby.elsewhere()).isFalse();
        assertThat(lobby.keepsIt()).isTrue();
        assertThat(smp.elsewhere()).isTrue();
    }

    private BankRequests requests() {
        Database database = core();
        return new BankRequests(database, System::currentTimeMillis);
    }

    @Test
    @DisplayName("a balance is read from the bank, and an account the bank does not know is said so")
    void balanceFromTheBank() {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        balances.put(ALEX, 1_250L);
        NetworkEconomy.serve(smpCalls, () -> Optional.of(bank), () -> true, requests());
        lobby.refresh();
        Economy network = new NetworkEconomy(lobbyCalls, lobbyRoles);

        assertThat(lobby.balance(network, ALEX)).hasValueSatisfying(known -> {
            assertThat(known.account()).isTrue();
            assertThat(known.amount()).isEqualTo(Money.of(1_250));
        });
        assertThat(lobby.balance(network, BEA)).hasValueSatisfying(unknown -> assertThat(unknown.account()).isFalse());
    }

    @Test
    @DisplayName("a balance is never made up when the bank cannot be reached")
    void noBalanceWithoutABank() {
        smpRoles.moveHome(NetworkEconomy.ROLE, "smp");
        lobby.refresh();

        assertThat(lobby.balance(new NetworkEconomy(lobbyCalls, lobbyRoles), ALEX)).isEmpty();
    }

    @Test
    @DisplayName("/pay goes to the bank as the same call every server's /pay uses, and the bank's own words come back")
    void payAsksTheBank() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        AtomicReference<Map<String, String>> asked = new AtomicReference<>();
        smpCalls.answerLater("economy.pay", data -> {
            asked.set(data);
            return CompletableFuture.completedFuture(Map.of("lines", "{\"text\":\"sent\"}\n{\"text\":\"taxed\"}",
                    "confirm", "false", "paid", "true"));
        });

        Optional<BankClient.Payment> answer = lobby.pay(ALEX, "Alex", BEA, Money.of(500), true)
                .get(5, TimeUnit.SECONDS);

        assertThat(asked.get()).containsEntry("payer", ALEX.toString()).containsEntry("payerName", "Alex")
                .containsEntry("target", BEA.toString()).containsEntry("amount", "500")
                .containsEntry("confirmed", "true");
        assertThat(answer).hasValueSatisfying(payment -> {
            assertThat(payment.lines()).containsExactly("{\"text\":\"sent\"}", "{\"text\":\"taxed\"}");
            assertThat(payment.paid()).isTrue();
            assertThat(payment.confirm()).isFalse();
        });
    }

    @Test
    @DisplayName("a payment that asks to be confirmed says so")
    void payNeedsConfirming() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        smpCalls.answerLater("economy.pay", data -> CompletableFuture.completedFuture(
                Map.of("lines", "", "confirm", "true", "paid", "false")));

        Optional<BankClient.Payment> answer = lobby.pay(ALEX, "Alex", BEA, Money.of(500), false)
                .get(5, TimeUnit.SECONDS);

        assertThat(answer).hasValueSatisfying(payment -> {
            assertThat(payment.confirm()).isTrue();
            assertThat(payment.lines()).isEmpty();
        });
    }

    @Test
    @DisplayName("nothing is paid, and nothing pretended, when no server holds the bank")
    void payWithoutABank() throws Exception {
        assertThat(lobby.pay(ALEX, "Alex", BEA, Money.of(500), false).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("an answer without lines is not a payment")
    void payGarbled() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        smpCalls.answerLater("economy.pay", data -> CompletableFuture.completedFuture(Map.of()));

        assertThat(lobby.pay(ALEX, "Alex", BEA, Money.of(500), false).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    // ------------------------------------------------------------------------------ /baltop

    private LeaderboardService leaderboard() {
        Database database = TestDatabases.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA);
        databases.add(database);
        AccountBook book = new AccountBook(database, new BalanceRule(), System::currentTimeMillis);
        book.load();
        book.open(ALEX, "Alex", Money.of(5_000));
        book.open(BEA, "Bea", Money.of(9_000));
        book.open(AccountBook.LOTTERY_POT, "Lottery", Money.of(1_000_000));
        return new LeaderboardService(book, System::currentTimeMillis);
    }

    @Test
    @DisplayName("the richest list is answered by the bank from its own ranking, with the asker's place")
    void topFromTheBank() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        BankTop.serve(smpCalls, leaderboard(), () -> true, () -> true, Runnable::run);

        Optional<BankTop.Top> top = lobby.top(ALEX).get(5, TimeUnit.SECONDS);

        assertThat(top).hasValueSatisfying(ranking -> {
            assertThat(ranking.off()).isFalse();
            assertThat(ranking.entries()).extracting(BankTop.Entry::name).containsExactly("Bea", "Alex");
            assertThat(ranking.entries()).extracting(BankTop.Entry::balance)
                    .containsExactly(Money.of(9_000), Money.of(5_000));
            assertThat(ranking.entries().getFirst().id()).isEqualTo(BEA);
            assertThat(ranking.place()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("only the ten richest are sent")
    void topIsShort() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        Database database = TestDatabases.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA);
        databases.add(database);
        AccountBook book = new AccountBook(database, new BalanceRule(), System::currentTimeMillis);
        book.load();
        for (int i = 1; i <= 15; i++) {
            book.open(UUID.randomUUID(), "P" + i, Money.of(i * 100L));
        }
        BankTop.serve(smpCalls, new LeaderboardService(book, System::currentTimeMillis), () -> true, () -> true,
                Runnable::run);

        Optional<BankTop.Top> top = lobby.top(ALEX).get(5, TimeUnit.SECONDS);

        assertThat(top).hasValueSatisfying(ranking -> {
            assertThat(ranking.entries()).hasSize(10);
            assertThat(ranking.entries().getFirst().name()).isEqualTo("P15");
            assertThat(ranking.place()).isZero();
        });
    }

    @Test
    @DisplayName("a switched-off list is answered as off, not as an empty one")
    void topOff() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        BankTop.serve(smpCalls, leaderboard(), () -> true, () -> false, Runnable::run);

        assertThat(lobby.top(ALEX).get(5, TimeUnit.SECONDS)).hasValueSatisfying(top -> {
            assertThat(top.off()).isTrue();
            assertThat(top.entries()).isEmpty();
        });
    }

    @Test
    @DisplayName("a server that lost the bank a moment ago does not rank for callers who asked it")
    void topNotFromAFormerBank() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        BankTop.serve(smpCalls, leaderboard(), () -> false, () -> true, Runnable::run);

        assertThat(lobby.top(ALEX).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("no ranking when no server holds the bank")
    void topWithoutABank() throws Exception {
        assertThat(lobby.top(ALEX).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("a ranking that never comes is given up on")
    void topTimesOut() throws Exception {
        smpRoles.claimHome(NetworkEconomy.ROLE);
        smpRoles.claim(NetworkEconomy.ROLE);
        smpCalls.answerLater("economy:top", data -> new CompletableFuture<>());
        BankClient quick = new BankClient(lobbyCalls, lobbyRoles, "lobby", id -> id, Duration.ofMillis(300));

        assertThat(quick.top(ALEX).get(5, TimeUnit.SECONDS)).isEmpty();
    }
}
