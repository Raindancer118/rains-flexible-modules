package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Dialect;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.jobs.model.Order;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** What decides who pays: the one write that moves a row off its open state, however the servers interleave. */
@Tag("database")
class PayOnceBooksTest {

    private static final int ROUNDS = 25;

    @TempDir
    Path folder;

    private TwoServers servers;
    private final UUID ana = UUID.randomUUID();

    @BeforeEach
    void start() {
        servers = new TwoServers(folder);
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    private static OrderBook.Ledger ledger(int progress, Order.State state) {
        return new OrderBook.Ledger("2026-10-10", 1, 0, false,
                new Order("zombies", "40 zombies", 40, Money.of(500), 1_000, 9_000, progress, state));
    }

    private static QuestDay day(String date, Quest.State state) {
        return new QuestDay(date, 3, List.of(new Quest("quarry", 300, Money.of(900), 300, state),
                new Quest("miner-iron", 80, Money.of(1500), 0, Quest.State.OPEN)), List.of());
    }

    /** A server that hears nothing from the others: what it holds is as old as its last read. */
    private OrderBook deafOrders(de.raindancer.core.data.sql.Database database) {
        OrderBook book = new OrderBook(database);
        book.load();
        return book;
    }

    private QuestBook deafQuests(de.raindancer.core.data.sql.Database database) {
        QuestBook book = new QuestBook(database);
        book.load();
        return book;
    }

    private static void onlyOnPostgres() {
        Assumptions.assumeTrue(TestDatabases.dialect() == Dialect.POSTGRESQL, "needs writers that do not queue up");
    }

    private static <T> List<T> together(List<java.util.concurrent.Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> running = new ArrayList<>();
        for (java.util.concurrent.Callable<T> job : jobs) {
            running.add(pool.submit(() -> {
                go.await();
                return job.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> each : running) {
            results.add(each.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    @DisplayName("an order another server pays between this one's read and write is not paid again")
    void orderLostBetweenReadAndWrite() {
        onlyOnPostgres();
        OrderBook smp = servers.orders("smp");
        Interleaved database = new Interleaved(servers.database());
        OrderBook arena = deafOrders(database);
        smp.putNow(ana, ledger(39, Order.State.OPEN));
        arena.of(ana);
        database.before("UPDATE order_ledger SET progress", () -> assertThat(smp.putNow(ana, ledger(40, Order.State.PAID))).isTrue());
        assertThat(arena.putNow(ana, ledger(40, Order.State.PAID))).as("the other one paid").isFalse();
        assertThat(servers.orders("third").of(ana).orElseThrow().order().state()).isEqualTo(Order.State.PAID);
    }

    @Test
    @DisplayName("a quest another server pays between this one's read and write is not paid again")
    void questLostBetweenReadAndWrite() {
        onlyOnPostgres();
        QuestBook smp = servers.quests("smp");
        Interleaved database = new Interleaved(servers.database());
        QuestBook arena = deafQuests(database);
        smp.putNow(ana, day("2026-10-10", Quest.State.OPEN));
        arena.of(ana);
        database.before("UPDATE quest SET progress", () -> assertThat(smp.putNow(ana, day("2026-10-10", Quest.State.PAID))).isTrue());
        assertThat(arena.putNow(ana, day("2026-10-10", Quest.State.PAID))).as("the other one paid").isFalse();
        assertThat(servers.quests("third").of(ana).orElseThrow().quests().getFirst().state()).isEqualTo(Quest.State.PAID);
    }

    @Test
    @DisplayName("an order paid and then told by a retry that it is open again stays paid")
    void orderStaysPaid() {
        OrderBook smp = servers.orders("smp");
        OrderBook arena = servers.orders("arena");
        smp.putNow(ana, ledger(39, Order.State.OPEN));
        servers.settle();
        arena.of(ana);
        assertThat(smp.putNow(ana, ledger(40, Order.State.PAID))).isTrue();
        servers.settle();
        assertThat(arena.putNow(ana, ledger(40, Order.State.OPEN))).as("a paid order is not reopened").isFalse();
        assertThat(servers.orders("third").of(ana).orElseThrow().order().state()).isEqualTo(Order.State.PAID);
    }

    @Test
    @DisplayName("a quest paid and then told by a retry that it is open again stays paid")
    void questStaysPaid() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = servers.quests("arena");
        smp.putNow(ana, day("2026-10-10", Quest.State.OPEN));
        servers.settle();
        arena.of(ana);
        assertThat(smp.putNow(ana, day("2026-10-10", Quest.State.PAID))).isTrue();
        servers.settle();
        assertThat(arena.putNow(ana, day("2026-10-10", Quest.State.OPEN))).as("a paid quest is not reopened").isFalse();
        assertThat(servers.quests("third").of(ana).orElseThrow().quests().getFirst().state()).isEqualTo(Quest.State.PAID);
    }

    @Test
    @DisplayName("a day made from a view in which a quest was still owed does not bring the debt back once it is paid")
    void paidDebtDoesNotComeBack() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = deafQuests(servers.database());
        smp.putNow(ana, day("2026-10-09", Quest.State.OWED));
        arena.of(ana);
        assertThat(smp.putNow(ana, day("2026-10-09", Quest.State.PAID))).isTrue();
        QuestDay carried = new QuestDay("2026-10-10", 3, List.of(day("2026-10-09", Quest.State.OWED).quests().getFirst()), List.of());
        assertThat(arena.putNow(ana, carried)).as("built from a day that has moved on").isFalse();
        assertThat(servers.quests("third").owing()).isEmpty();
    }

    @Test
    @DisplayName("a ledger made from a view in which an order was still owed does not bring the debt back once it is paid")
    void paidOrderDebtDoesNotComeBack() {
        OrderBook smp = servers.orders("smp");
        OrderBook arena = deafOrders(servers.database());
        smp.putNow(ana, ledger(40, Order.State.OWED));
        arena.of(ana);
        assertThat(smp.putNow(ana, ledger(40, Order.State.PAID))).isTrue();
        OrderBook.Ledger next = new OrderBook.Ledger("2026-10-10", 2, 0, false, ledger(40, Order.State.OWED).order());
        assertThat(arena.putNow(ana, next)).isFalse();
        assertThat(servers.orders("third").actionable(0)).isEmpty();
    }

    @Test
    @DisplayName("wiping a player's quests keeps what is owed to them")
    void clearKeepsDebts() {
        QuestBook smp = servers.quests("smp");
        smp.putNow(ana, day("2026-10-10", Quest.State.OWED));
        assertThat(servers.quests("arena").clear(ana)).isFalse();
        assertThat(servers.quests("third").owing()).containsOnlyKeys(ana);
    }

    @Test
    @DisplayName("an order two servers see done and both pay is paid by one, every time")
    void ordersRace() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID player = UUID.randomUUID();
            OrderBook smp = deafOrders(servers.database());
            OrderBook arena = deafOrders(servers.database());
            smp.putNow(player, ledger(39, Order.State.OPEN));
            smp.of(player);
            arena.of(player);
            List<Boolean> won = together(List.of(() -> smp.putNow(player, ledger(40, Order.State.PAID)),
                    () -> arena.putNow(player, ledger(40, Order.State.PAID))));
            assertThat(won).as("round " + round).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    @DisplayName("a quest two servers see done and both pay is paid by one, every time")
    void questsRace() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID player = UUID.randomUUID();
            QuestBook smp = deafQuests(servers.database());
            QuestBook arena = deafQuests(servers.database());
            smp.putNow(player, day("2026-10-10", Quest.State.OPEN));
            smp.of(player);
            arena.of(player);
            List<Boolean> won = together(List.of(() -> smp.putNow(player, day("2026-10-10", Quest.State.PAID)),
                    () -> arena.putNow(player, day("2026-10-10", Quest.State.PAID))));
            assertThat(won).as("round " + round).containsExactlyInAnyOrder(true, false);
        }
    }
}
