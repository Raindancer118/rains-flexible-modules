package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.EconomyResult.Outcome;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Transaction;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BalanceRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger against a real SQLite file: what goes in, what survives a restart, and what two threads do to it. */
class AccountBookTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Money most = Money.of(1_000_000_00L);
    private Database database;
    private AccountBook book;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private AccountBook reopened() {
        book.flush();
        database.close();
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        AccountBook fresh = new AccountBook(database, new BalanceRule(), clock::get);
        fresh.load();
        return fresh;
    }

    @Test
    @DisplayName("an account opens once with its starting balance, and opening it again changes nothing")
    void opening() {
        book.open(alice, "Alice", Money.of(10_000));
        book.open(alice, "Alice", Money.of(99_999));
        assertThat(book.balance(alice)).isEqualTo(Money.of(10_000));
        assertThat(book.find(alice)).isPresent();
        assertThat(book.balance(bob)).isEqualTo(Money.ZERO);
        assertThat(book.findByName("alice")).map(account -> account.id()).contains(alice);
    }

    @Test
    @DisplayName("money in and out moves the balance and refuses what the rule refuses")
    void changes() {
        book.open(alice, "Alice", Money.ZERO);
        assertThat(book.change(alice, Money.of(500), TransactionKind.REWARD, "a zombie", null, most).succeeded()).isTrue();
        EconomyResult refused = book.change(alice, Money.of(-501), TransactionKind.BUY, "bread", null, most);
        assertThat(refused.outcome()).isEqualTo(Outcome.NOT_ENOUGH);
        assertThat(refused.balance()).isEqualTo(Money.of(500));
        assertThat(book.change(bob, Money.of(5), TransactionKind.ADMIN, "", null, most).outcome())
                .as("no account").isEqualTo(Outcome.NO_ACCOUNT);
    }

    @Test
    @DisplayName("a transfer is both sides or neither, and tax leaves the receiver as its own line")
    void transfers() {
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.ZERO);
        EconomyResult paid = book.transfer(alice, bob, Money.of(400), Money.of(40), TransactionKind.PAY, "rent", most);
        assertThat(paid.succeeded()).isTrue();
        assertThat(book.balance(alice)).isEqualTo(Money.of(600));
        assertThat(book.balance(bob)).isEqualTo(Money.of(360));

        assertThat(book.transfer(alice, bob, Money.of(601), Money.ZERO, TransactionKind.PAY, "", most).outcome())
                .isEqualTo(Outcome.NOT_ENOUGH);
        assertThat(book.balance(alice)).isEqualTo(Money.of(600));
        assertThat(book.balance(bob)).isEqualTo(Money.of(360));

        book.freeze(bob, true);
        assertThat(book.transfer(alice, bob, Money.of(1), Money.ZERO, TransactionKind.PAY, "", most).outcome())
                .isEqualTo(Outcome.FROZEN);
        assertThat(book.balance(alice)).as("a refused receiver took nothing from the sender").isEqualTo(Money.of(600));
    }

    @Test
    @DisplayName("everything survives a restart: balances, names, frozen accounts and the statement")
    void persistence() {
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.ZERO);
        book.transfer(alice, bob, Money.of(250), Money.ZERO, TransactionKind.PAY, "rent", most);
        book.freeze(bob, true);

        AccountBook fresh = reopened();
        assertThat(fresh.balance(alice)).isEqualTo(Money.of(750));
        assertThat(fresh.balance(bob)).isEqualTo(Money.of(250));
        assertThat(fresh.find(bob).orElseThrow().frozen()).isTrue();

        List<Transaction> statement = fresh.history(alice, 10, 0);
        assertThat(statement).extracting(Transaction::kind)
                .containsExactly(TransactionKind.PAY, TransactionKind.OPENING);
        assertThat(statement.getFirst().delta()).isEqualTo(Money.of(-250));
        assertThat(statement.getFirst().other()).isEqualTo(bob);
        assertThat(statement.getFirst().reason()).isEqualTo("rent");
    }

    @Test
    @DisplayName("a note is issued with the money it costs, and paid in exactly once")
    void notes() {
        book.open(alice, "Alice", Money.of(10_000));
        book.open(bob, "Bob", Money.ZERO);
        EconomyResult issued = book.issueCash(alice, Map.of("S1", Money.of(5_000)), Map.of(), Money.of(5_000), Money.of(100), most);
        assertThat(issued.succeeded()).isTrue();
        assertThat(book.balance(alice)).isEqualTo(Money.of(4_900));
        assertThat(book.isOutstanding("S1")).isTrue();

        assertThat(book.redeemCash(bob, List.of("S1"), Map.of(), Money.of(5_000), most).succeeded()).isTrue();
        assertThat(book.balance(bob)).isEqualTo(Money.of(5_000));
        assertThat(book.redeemCash(bob, List.of("S1"), Map.of(), Money.of(5_000), most).outcome())
                .as("the duplicate").isEqualTo(Outcome.REFUSED);
        assertThat(book.balance(bob)).isEqualTo(Money.of(5_000));
    }

    @Test
    @DisplayName("an outstanding note survives a restart, and a redeemed one stays redeemed")
    void notesSurvive() {
        book.open(alice, "Alice", Money.of(10_000));
        book.issueCash(alice, Map.of("KEEP", Money.of(1_000), "SPENT", Money.of(1_000)), Map.of(), Money.of(2_000),
                Money.ZERO, most);
        book.redeemCash(alice, List.of("SPENT"), Map.of(), Money.of(1_000), most);

        AccountBook fresh = reopened();
        assertThat(fresh.isOutstanding("KEEP")).isTrue();
        assertThat(fresh.isOutstanding("SPENT")).isFalse();
    }

    @Test
    @DisplayName("coins in circulation are counted, survive a restart, and no more can be paid in than are out")
    void coinCirculation() {
        book.open(alice, "Alice", Money.of(10_000));
        assertThat(book.issueCash(alice, Map.of(), Map.of(Money.of(100), 5), Money.of(500), Money.ZERO, most)
                .succeeded()).isTrue();
        assertThat(book.coinsOut(Money.of(100))).isEqualTo(5);
        assertThat(book.redeemCash(alice, List.of(), Map.of(Money.of(100), 6), Money.of(600), most).outcome())
                .as("a sixth coin was never issued").isEqualTo(Outcome.REFUSED);
        assertThat(book.redeemCash(alice, List.of(), Map.of(Money.of(100), 2), Money.of(200), most).succeeded()).isTrue();

        AccountBook fresh = reopened();
        assertThat(fresh.coinsOut(Money.of(100))).isEqualTo(3);
        assertThat(fresh.balance(alice)).isEqualTo(Money.of(9_700));
    }

    @Test
    @DisplayName("issuing more cash than the balance holds issues nothing")
    void cashNeedsMoney() {
        book.open(alice, "Alice", Money.of(100));
        assertThat(book.issueCash(alice, Map.of("X", Money.of(1_000)), Map.of(), Money.of(1_000), Money.ZERO, most).outcome())
                .isEqualTo(Outcome.NOT_ENOUGH);
        assertThat(book.isOutstanding("X")).isFalse();
    }

    @Test
    @DisplayName("an admin set records the difference, so the statement still adds up")
    void setting() {
        book.open(alice, "Alice", Money.of(100));
        book.set(alice, Money.of(5_000), "correction");
        assertThat(book.balance(alice)).isEqualTo(Money.of(5_000));
        assertThat(reopened().history(alice, 1, 0).getFirst().delta()).isEqualTo(Money.of(4_900));
    }

    @Test
    @DisplayName("old statement lines are forgotten; balances are not")
    void forgetting() {
        book.open(alice, "Alice", Money.of(100));
        clock.set(10_000_000L);
        book.change(alice, Money.of(50), TransactionKind.REWARD, "", null, most);
        book.flush();
        assertThat(book.forgetHistoryBefore(5_000_000L)).as("the openings of alice, the lottery pot, the auction escrow and the raffle pot").isEqualTo(4);
        assertThat(book.history(alice, 10, 0)).hasSize(1);
        assertThat(book.balance(alice)).isEqualTo(Money.of(150));
    }

    @Test
    @DisplayName("a game takes the stake and pays the win in one change, and cannot be played without the stake")
    void games() {
        book.open(alice, "Alice", Money.of(1_000));
        assertThat(book.play(alice, Money.of(300), Money.of(582), "coin flip", most).balance()).isEqualTo(Money.of(1_282));
        assertThat(book.play(alice, Money.of(300), Money.ZERO, "coin flip", most).balance()).isEqualTo(Money.of(982));
        assertThat(book.play(alice, Money.of(5_000), Money.of(10_000), "coin flip", most).outcome())
                .isEqualTo(Outcome.NOT_ENOUGH);
        assertThat(book.balance(alice)).isEqualTo(Money.of(982));
    }

    @Test
    @DisplayName("a duel moves the loser's stake to the winner less the cut, and needs both to have it")
    void duels() {
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.of(200));
        assertThat(book.duel(alice, bob, Money.of(500), Money.ZERO, "duel", most).outcome())
                .as("bob cannot cover it").isEqualTo(Outcome.NOT_ENOUGH);
        assertThat(book.duel(alice, bob, Money.of(200), Money.of(10), "duel", most).succeeded()).isTrue();
        assertThat(book.balance(alice)).isEqualTo(Money.of(1_190));
        assertThat(book.balance(bob)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("numbered tickets fill the pot less the cut, survive a restart; a draw pays from the pot and the rest rolls over")
    void lottery() {
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.of(1_000));
        assertThat(book.buyTickets(alice, List.of(List.of(1, 2, 3, 4), List.of(5, 6, 7, 8)), Money.of(100),
                Money.of(10), most).succeeded()).isTrue();
        assertThat(book.buyTickets(bob, List.of(List.of(9, 10, 11, 12)), Money.of(100), Money.of(10), most)
                .succeeded()).isTrue();
        assertThat(book.buyTickets(bob, java.util.Collections.nCopies(50, List.of(1, 2, 3, 4)), Money.of(100),
                Money.of(10), most).outcome()).isEqualTo(Outcome.NOT_ENOUGH);
        assertThat(book.balance(AccountBook.LOTTERY_POT)).as("300 in, 30 cut").isEqualTo(Money.of(270));
        book.scheduleDraw(99L);

        AccountBook fresh = reopened();
        assertThat(fresh.tickets()).hasSize(3);
        assertThat(fresh.ticketsOf(alice)).extracting(de.raindancer.modules.economy.model.LotteryTicket::numbers)
                .containsExactly(List.of(1, 2, 3, 4), List.of(5, 6, 7, 8));
        assertThat(fresh.nextDrawAt()).isEqualTo(99L);
        long drawn = fresh.drawNumber();

        assertThat(fresh.settleDraw(Map.of(bob, Money.of(100)), 500L)).isEqualTo(Money.of(100));
        assertThat(fresh.balance(bob)).isEqualTo(Money.of(1_000));
        assertThat(fresh.balance(AccountBook.LOTTERY_POT)).as("what nobody won rolls over").isEqualTo(Money.of(170));
        assertThat(fresh.tickets()).isEmpty();
        assertThat(fresh.drawNumber()).isEqualTo(drawn + 1);

        book = fresh;
        AccountBook again = reopened();
        assertThat(again.tickets()).isEmpty();
        assertThat(again.drawNumber()).isEqualTo(drawn + 1);
        assertThat(again.balance(AccountBook.LOTTERY_POT)).isEqualTo(Money.of(170));
    }

    @Test
    @DisplayName("a wage is paid when due, a missed one is counted, and too many in a row end the job")
    void payroll() {
        book.open(alice, "Alice", Money.of(250));
        book.open(bob, "Bob", Money.ZERO);
        de.raindancer.modules.economy.model.Contract job = new de.raindancer.modules.economy.model.Contract(
                UUID.randomUUID(), alice, "Alice", bob, "Bob", Money.of(100), 60, 1_000_000L, 0, "miner", 0);
        book.hire(job);
        assertThat(book.contractsOf(bob)).hasSize(1);

        assertThat(book.payroll(999_999L, 3, most)).as("not due yet").isEmpty();
        assertThat(book.payroll(1_000_000L, 3, most)).extracting(de.raindancer.modules.economy.model.Payday::kind)
                .containsExactly(de.raindancer.modules.economy.model.Payday.Kind.PAID);
        assertThat(book.balance(bob)).isEqualTo(Money.of(100));
        long next = book.contractsOf(bob).getFirst().nextAt();
        assertThat(next).isEqualTo(1_000_000L + 3_600_000L);

        book.payroll(next, 3, most);
        assertThat(book.balance(alice)).isEqualTo(Money.of(50));
        long later = book.contractsOf(bob).getFirst().nextAt();
        assertThat(book.payroll(later, 2, most)).extracting(de.raindancer.modules.economy.model.Payday::kind)
                .containsExactly(de.raindancer.modules.economy.model.Payday.Kind.MISSED);
        long last = book.contractsOf(bob).getFirst().nextAt();
        assertThat(book.payroll(last, 2, most)).extracting(de.raindancer.modules.economy.model.Payday::kind)
                .containsExactly(de.raindancer.modules.economy.model.Payday.Kind.ENDED);
        assertThat(book.contractsOf(bob)).isEmpty();
    }

    @Test
    @DisplayName("a job survives a restart, and one ended stays ended")
    void contractsSurvive() {
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.ZERO);
        UUID kept = UUID.randomUUID();
        UUID ended = UUID.randomUUID();
        book.hire(new de.raindancer.modules.economy.model.Contract(kept, alice, "Alice", bob, "Bob", Money.of(5), 30,
                5L, 0, "guard", 1L));
        book.hire(new de.raindancer.modules.economy.model.Contract(ended, alice, "Alice", bob, "Bob", Money.of(5), 30,
                5L, 0, "cook", 1L));
        book.endContract(ended);

        AccountBook fresh = reopened();
        assertThat(fresh.contractsOf(alice)).extracting(de.raindancer.modules.economy.model.Contract::id)
                .containsExactly(kept);
        assertThat(fresh.contractsOf(alice).getFirst().title()).isEqualTo("guard");
    }

    @Test
    @DisplayName("a service contract — rent — is paid on the statement as a contract, and stays one after a restart")
    void serviceContract() {
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.ZERO);
        UUID rent = UUID.randomUUID();
        book.hire(new de.raindancer.modules.economy.model.Contract(rent, alice, "Alice", bob, "Bob", Money.of(300), 1440,
                10L, 0, "Apartment 3", 1L, de.raindancer.modules.economy.model.Contract.Kind.SERVICE));

        assertThat(book.payroll(10L, 3, most)).extracting(de.raindancer.modules.economy.model.Payday::kind)
                .containsExactly(de.raindancer.modules.economy.model.Payday.Kind.PAID);
        assertThat(book.balance(bob)).isEqualTo(Money.of(300));
        assertThat(book.history(bob, 5, 0)).anySatisfy(line -> {
            assertThat(line.kind()).isEqualTo(de.raindancer.modules.economy.model.TransactionKind.CONTRACT);
            assertThat(line.reason()).isEqualTo("Contract: Apartment 3");
        });

        AccountBook fresh = reopened();
        assertThat(fresh.contractsOf(alice).getFirst().kind())
                .isEqualTo(de.raindancer.modules.economy.model.Contract.Kind.SERVICE);
    }

    @Test
    @DisplayName("hammered from eight threads at once, not one cent appears or disappears")
    void concurrency() throws InterruptedException {
        int people = 8;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            book.open(id, "p" + i, Money.of(10_000));
        }
        ExecutorService pool = Executors.newFixedThreadPool(people);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < people; t++) {
            int seed = t;
            pool.submit(() -> {
                start.await();
                java.util.Random random = new java.util.Random(seed);
                for (int i = 0; i < 2_000; i++) {
                    UUID from = ids.get(random.nextInt(people));
                    UUID to = ids.get(random.nextInt(people));
                    if (!from.equals(to)) {
                        book.transfer(from, to, Money.of(1 + random.nextInt(300)), Money.ZERO,
                                TransactionKind.PAY, "", most);
                    }
                    if (i % 500 == 0) {
                        book.flush();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        long total = ids.stream().mapToLong(id -> book.balance(id).minor()).sum();
        assertThat(total).isEqualTo(people * 10_000L);
        AccountBook fresh = reopened();
        long stored = ids.stream().mapToLong(id -> fresh.balance(id).minor()).sum();
        assertThat(stored).isEqualTo(people * 10_000L);
        assertThat(fresh.all()).allMatch(account -> !account.balance().isNegative());
        assertThat(fresh.all()).anyMatch(account -> AccountBook.isSystem(account.id()));
    }
}
