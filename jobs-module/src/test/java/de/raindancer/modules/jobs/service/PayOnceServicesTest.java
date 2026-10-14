package de.raindancer.modules.jobs.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Dialect;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.jobs.JobsSettings;
import de.raindancer.modules.jobs.OrderSettings;
import de.raindancer.modules.jobs.QuestSettings;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.Order;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.store.GoalBook;
import de.raindancer.modules.jobs.store.Interleaved;
import de.raindancer.modules.jobs.store.JobsDatabase;
import de.raindancer.modules.jobs.store.OrderBook;
import de.raindancer.modules.jobs.store.QuestBook;
import de.raindancer.modules.jobs.store.QuestCatalogue;
import de.raindancer.modules.jobs.store.TemplateCatalogue;
import de.raindancer.modules.jobs.store.WorkCatalogue;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Two servers of one network over one database: whatever they race to finish is paid once. */
@Tag("database")
class PayOnceServicesTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final int ROUNDS = 20;

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(20_000L * DAY + 3_600_000L);
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Bank bank = new Bank();
    private final List<Database> databases = new ArrayList<>();
    private QuestCatalogue quests;
    private WorkCatalogue works;
    private TemplateCatalogue goals;

    private static final class Bank implements Economy {
        final Map<UUID, Money> balances = new ConcurrentHashMap<>();
        final List<String> payments = Collections.synchronizedList(new ArrayList<>());
        volatile boolean treasuryEmpty;
        volatile boolean throwing;

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
            if (throwing) {
                throw new IllegalStateException("the economy is down");
            }
            if (treasuryEmpty && amount.isPositive()) {
                return EconomyResult.failed(EconomyResult.Outcome.TREASURY_EMPTY, amount, balance(player));
            }
            payments.add(player + " " + reason);
            balances.merge(player, amount, Money::plus);
            return EconomyResult.done(amount, balances.get(player));
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return deposit(player, amount.negate(), reason);
        }

        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            withdraw(from, amount, reason);
            return deposit(to, amount, reason);
        }

        long paid(UUID player, String what) {
            synchronized (payments) {
                return payments.stream().filter(each -> each.startsWith(player + " " + what)).count();
            }
        }
    }

    @BeforeEach
    void start() {
        Economies.provide(mock(org.bukkit.plugin.Plugin.class), bank);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of());
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[0]);
        quests = new QuestCatalogue(new YamlStore(folder.resolve("quests.yml")), () -> new ByteArrayInputStream("""
                quests:
                  zombies: { task: kill, things: [ zombie ], amount: 10, pay: 100 }
                """.getBytes(StandardCharsets.UTF_8)));
        quests.reload();
        works = new WorkCatalogue(new YamlStore(folder.resolve("orders.yml")), () -> new ByteArrayInputStream("""
                work:
                  zombies: { name: Zombies, task: kill, things: [ zombie ], value: 8, rate: 150, hardness: 0.05 }
                """.getBytes(StandardCharsets.UTF_8)));
        works.reload();
        goals = new TemplateCatalogue(new YamlStore(folder.resolve("jobs.yml")), () -> new ByteArrayInputStream("""
                goals:
                  cod: { kind: deliver, items: [ cod ], start: 100, least: 10, most: 1000, days: 5 }
                  wheat: { kind: deliver, items: [ wheat ], start: 50, days: 4 }
                """.getBytes(StandardCharsets.UTF_8)));
        goals.reload();
    }

    @AfterEach
    void stop() {
        databases.forEach(Database::close);
        Economies.clear();
    }

    private Database database() {
        Database database = TestDatabases.open(folder.resolve("jobs.db"), JobsDatabase.SCHEMA);
        databases.add(database);
        return database;
    }

    private static void onlyOnPostgres() {
        Assumptions.assumeTrue(TestDatabases.dialect() == Dialect.POSTGRESQL, "needs writers that do not queue up");
    }

    private static Player playing(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static void together(Runnable... jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.length);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> running = new ArrayList<>();
        for (Runnable job : jobs) {
            running.add(pool.submit((Callable<Void>) () -> {
                go.await();
                job.run();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> each : running) {
            each.get();
        }
        pool.shutdown();
    }

    // ---------------------------------------------------------------------------- quests

    private QuestService quests(Database database) {
        QuestBook book = new QuestBook(database);
        book.load();
        return new QuestService(server, quests, book, messages, Log.of("jobs"), now::get, ZoneOffset.UTC, new Random(5),
                new QuestSettings(true, 3, 2, "5000", 35, 60, 8, 25, 100, true, 1));
    }

    private static void killZombies(QuestService service, Player player) {
        int amount = service.today(player.getUniqueId()).quests().getFirst().amount();
        service.progress(player, QuestTask.KILL, "ZOMBIE", amount);
    }

    @Test
    @DisplayName("a quest two servers finish at the same moment is paid once")
    void questRace() throws Exception {
        QuestService smp = quests(database());
        QuestService arena = quests(database());
        for (int round = 0; round < ROUNDS; round++) {
            UUID id = UUID.randomUUID();
            Player player = playing(id);
            smp.today(id);
            arena.today(id);
            together(() -> killZombies(smp, player), () -> killZombies(arena, player));
            assertThat(bank.paid(id, "Quest:")).as("round " + round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an owed quest two servers pay on the same minute is paid once")
    void questOwedRace() {
        onlyOnPostgres();
        UUID id = UUID.randomUUID();
        Player player = playing(id);
        QuestService smp = quests(database());
        Interleaved second = new Interleaved(database());
        QuestService arena = quests(second);
        bank.treasuryEmpty = true;
        killZombies(smp, player);
        bank.treasuryEmpty = false;
        assertThat(smp.today(id).quests().getFirst().state()).isEqualTo(Quest.State.OWED);
        second.before("UPDATE quest SET progress", smp::tick);
        arena.tick();
        assertThat(bank.paid(id, "Quest:")).isEqualTo(1);
    }

    @Test
    @DisplayName("a payment that throws leaves the quest owed, and the next minute pays it once")
    void questPayThrows() {
        UUID id = UUID.randomUUID();
        Player player = playing(id);
        QuestService smp = quests(database());
        bank.throwing = true;
        killZombies(smp, player);
        bank.throwing = false;
        assertThat(smp.today(id).quests().getFirst().state()).isEqualTo(Quest.State.OWED);
        smp.tick();
        smp.tick();
        assertThat(bank.paid(id, "Quest:")).isEqualTo(1);
        assertThat(smp.today(id).quests().getFirst().state()).isEqualTo(Quest.State.PAID);
    }

    @Test
    @DisplayName("an owed quest the treasury refuses again is put back owed and paid later, once")
    void questOwedPutBack() {
        UUID id = UUID.randomUUID();
        Player player = playing(id);
        QuestService smp = quests(database());
        bank.treasuryEmpty = true;
        killZombies(smp, player);
        smp.tick();
        assertThat(smp.today(id).quests().getFirst().state()).isEqualTo(Quest.State.OWED);
        bank.treasuryEmpty = false;
        smp.tick();
        smp.tick();
        assertThat(bank.paid(id, "Quest:")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------- orders

    private OrderService orders(Database database) {
        OrderBook book = new OrderBook(database);
        book.load();
        return new OrderService(server, works, book, messages, Log.of("jobs"), now::get, ZoneOffset.UTC, new Random(4),
                OrderSettings.DEFAULTS);
    }

    private OrderService.Offer take(OrderService service, Player player) {
        OrderService.Offer offer = service.ask(player.getUniqueId(), de.raindancer.core.social.economy.Fees.amount("1000")).offer();
        assertThat(service.accept(player, offer)).isTrue();
        return offer;
    }

    @Test
    @DisplayName("an order two servers finish at the same moment is paid once")
    void orderRace() throws Exception {
        OrderService smp = orders(database());
        OrderService arena = orders(database());
        for (int round = 0; round < ROUNDS; round++) {
            UUID id = UUID.randomUUID();
            Player player = playing(id);
            OrderService.Offer offer = take(smp, player);
            arena.preload(id);
            together(() -> smp.progress(player, QuestTask.KILL, "ZOMBIE", offer.units()),
                    () -> arena.progress(player, QuestTask.KILL, "ZOMBIE", offer.units()));
            assertThat(bank.paid(id, "Order:")).as("round " + round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a server that learns too late that its order was paid elsewhere neither pays nor reopens it")
    void orderNotReopened() {
        UUID id = UUID.randomUUID();
        Player player = playing(id);
        OrderService smp = orders(database());
        OrderService arena = orders(database());
        OrderService.Offer offer = take(smp, player);
        arena.preload(id);
        smp.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        assertThat(bank.paid(id, "Order:")).isEqualTo(1);
        arena.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        arena.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        smp.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        assertThat(bank.paid(id, "Order:")).isEqualTo(1);
        assertThat(orders(database()).current(id).orElseThrow().state()).isEqualTo(Order.State.PAID);
    }

    @Test
    @DisplayName("an owed order two servers pay on the same minute is paid once")
    void orderOwedRace() {
        onlyOnPostgres();
        UUID id = UUID.randomUUID();
        Player player = playing(id);
        OrderService smp = orders(database());
        Interleaved second = new Interleaved(database());
        OrderService arena = orders(second);
        OrderService.Offer offer = take(smp, player);
        bank.treasuryEmpty = true;
        smp.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        bank.treasuryEmpty = false;
        assertThat(smp.current(id).orElseThrow().state()).isEqualTo(Order.State.OWED);
        second.before("UPDATE order_ledger SET progress", smp::tick);
        arena.tick();
        assertThat(bank.paid(id, "Order:")).isEqualTo(1);
    }

    @Test
    @DisplayName("a payment that throws leaves the order owed, and the next minute pays it once")
    void orderPayThrows() {
        UUID id = UUID.randomUUID();
        Player player = playing(id);
        OrderService smp = orders(database());
        OrderService.Offer offer = take(smp, player);
        bank.throwing = true;
        smp.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        bank.throwing = false;
        assertThat(smp.current(id).orElseThrow().state()).isEqualTo(Order.State.OWED);
        smp.tick();
        smp.tick();
        assertThat(bank.paid(id, "Order:")).isEqualTo(1);
    }

    @Test
    @DisplayName("an owed order the treasury refuses again is put back owed and paid later, once")
    void orderOwedPutBack() {
        UUID id = UUID.randomUUID();
        Player player = playing(id);
        OrderService smp = orders(database());
        OrderService.Offer offer = take(smp, player);
        bank.treasuryEmpty = true;
        smp.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        smp.tick();
        assertThat(smp.current(id).orElseThrow().state()).isEqualTo(Order.State.OWED);
        bank.treasuryEmpty = false;
        smp.tick();
        smp.tick();
        assertThat(bank.paid(id, "Order:")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------- goals

    private record Board(GoalService service, GoalBook book) {
    }

    private Board board(Database database) {
        GoalBook book = new GoalBook(database);
        book.load();
        return new Board(new GoalService(server, goals, book, messages, Log.of("jobs"), now::get, new Random(7),
                new JobsSettings(2, 100, 30, "5000", "", true, true, 100)), book);
    }

    @Test
    @DisplayName("a goal ended on a server that has not seen the hand-ins pays them as the database counted them")
    void goalPaidFromTheDatabase() {
        UUID ana = UUID.randomUUID();
        Board smp = board(database());
        smp.service().tick();
        Board arena = board(database());
        Goal goal = arena.service().goals().getFirst();
        smp.book().give(goal.number(), ana, 40);
        assertThat(arena.service().goal(goal.number()).orElseThrow().given()).as("arena has not heard").isEmpty();
        assertThat(arena.service().end(goal.number())).isTrue();
        assertThat(bank.paid(ana, "Server goal:")).isEqualTo(1);
    }

    @Test
    @DisplayName("a goal two servers end at the same moment is paid once")
    void goalRace() throws Exception {
        UUID ana = UUID.randomUUID();
        Board smp = board(database());
        Board arena = board(database());
        for (int round = 0; round < ROUNDS; round++) {
            smp.service().tick();
            arena.book().load();
            Goal goal = smp.service().goals().getFirst();
            smp.book().give(goal.number(), ana, 40);
            arena.book().load();
            together(() -> smp.service().end(goal.number()), () -> arena.service().end(goal.number()));
            assertThat(bank.paid(ana, "Server goal:")).as("round " + round).isEqualTo(round + 1);
        }
    }

    @Test
    @DisplayName("a goal whose payment throws is owed, and paid on a later minute, once")
    void goalPayThrows() {
        UUID ana = UUID.randomUUID();
        Board smp = board(database());
        smp.service().tick();
        Goal goal = smp.service().goals().getFirst();
        smp.book().give(goal.number(), ana, 40);
        bank.throwing = true;
        smp.service().end(goal.number());
        bank.throwing = false;
        assertThat(smp.book().owed()).containsKey(ana);
        smp.service().tick();
        assertThat(smp.book().owed()).isEmpty();
        assertThat(bank.paid(ana, "Server goal")).isEqualTo(1);
    }

    @Test
    @DisplayName("a goal ended with no economy owes what it would have paid instead of dropping it")
    void goalWithoutEconomy() {
        UUID ana = UUID.randomUUID();
        Board smp = board(database());
        smp.service().tick();
        Goal goal = smp.service().goals().getFirst();
        smp.book().give(goal.number(), ana, 40);
        smp.service().poolNow();
        Economies.clear();
        smp.service().end(goal.number());
        assertThat(smp.book().owed()).containsKey(ana);
    }
}
