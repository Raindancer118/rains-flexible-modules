package de.raindancer.modules.jobs.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.jobs.OrderSettings;
import de.raindancer.modules.jobs.model.Order;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.store.OrderBook;
import de.raindancer.modules.jobs.store.WorkCatalogue;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(20_000L * DAY + 3_600_000L);
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Bank bank = new Bank();
    private final UUID ana = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private OrderService service;

    private static final class Bank implements Economy {
        final Map<UUID, Money> balances = new HashMap<>();

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
            return EconomyResult.done(amount, balances.get(player));
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return deposit(player, amount.negate(), reason);
        }

        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            withdraw(from, amount, reason);
            return deposit(to, amount, reason);
        }
    }

    private static Money coins(String written) {
        return Fees.amount(written);
    }

    @BeforeEach
    void start() {
        Economies.provide(mock(org.bukkit.plugin.Plugin.class), bank);
        when(player.getUniqueId()).thenReturn(ana);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayer(ana)).thenReturn(player);
        WorkCatalogue works = new WorkCatalogue(new YamlStore(folder.resolve("orders.yml")), () ->
                new ByteArrayInputStream("""
                        work:
                          zombies: { name: Zombies, task: kill, things: [ zombie ], value: 8, rate: 150, hardness: 0.05 }
                          wardens: { name: Wardens, task: kill, things: [ warden ], value: 750, rate: 6, hardness: 0.85 }
                          dragons: { name: Ender Dragons, task: kill, things: [ ender_dragon ], value: 3000, rate: 2, hardness: 1 }
                        """.getBytes(StandardCharsets.UTF_8)));
        works.reload();
        OrderBook book = new OrderBook(new YamlStore(folder.resolve("order-progress.yml")));
        book.load();
        service = new OrderService(server, works, book, messages, Log.of("jobs"), now::get, ZoneOffset.UTC,
                new Random(4), OrderSettings.DEFAULTS);
    }

    @AfterEach
    void stop() {
        Economies.clear();
    }

    @Test
    @DisplayName("a small amount is easy work with hours to do it; a vast one is the hardest work in minutes")
    void scales() {
        OrderService.Offer small = service.ask(ana, coins("1000"), false).offer();
        assertThat(small.work().id()).isEqualTo("zombies");
        assertThat(small.time()).isGreaterThan(Duration.ofHours(3));
        OrderService.Offer vast = service.ask(ana, coins("100000000000"), false).offer();
        assertThat(vast.work().id()).isIn("wardens", "dragons");
        assertThat(vast.time()).isLessThan(Duration.ofHours(1));
        assertThat(vast.pay()).isEqualTo(coins("100000000000"));
    }

    @Test
    @DisplayName("too little, too much, nothing to offer, and switched off are refused with a reason")
    void refusals() {
        assertThat(service.ask(ana, coins("5"), false).refusal()).isEqualTo("jobs.order.too-little");
        assertThat(service.ask(ana, coins("200000000000"), false).refusal()).isEqualTo("jobs.order.too-much");
        service.settings(new OrderSettings(false, 3, 5, "100", "1000", "100000000000", 0.15, 750));
        assertThat(service.ask(ana, coins("1000"), false).refusal()).isEqualTo("jobs.order.switched-off");
    }

    @Test
    @DisplayName("taken, it counts what it asks for and pays the amount asked when done in time")
    void doneInTime() {
        OrderService.Offer offer = service.ask(ana, coins("1000"), false).offer();
        assertThat(service.accept(player, offer)).isTrue();
        assertThat(service.ask(ana, coins("1000"), false).refusal()).as("one at a time").isEqualTo("jobs.order.busy");
        service.progress(player, QuestTask.KILL, "SKELETON", 500);
        assertThat(service.current(ana).orElseThrow().progress()).isZero();
        service.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        assertThat(service.current(ana).orElseThrow().state()).isEqualTo(Order.State.PAID);
        assertThat(bank.balance(ana)).isEqualTo(coins("1000"));
    }

    @Test
    @DisplayName("out of time, it fails and pays nothing, and kills after the end count for nothing")
    void tooLate() {
        OrderService.Offer offer = service.ask(ana, coins("1000"), false).offer();
        service.accept(player, offer);
        now.addAndGet(offer.time().toMillis() + 1);
        service.progress(player, QuestTask.KILL, "ZOMBIE", offer.units());
        service.tick();
        assertThat(service.current(ana).orElseThrow().state()).isEqualTo(Order.State.FAILED);
        assertThat(bank.balance(ana)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("so many orders and re-rolls a day; a new day starts the count again")
    void limits() {
        for (int i = 0; i < 3; i++) {
            OrderService.Offer offer = service.ask(ana, coins("1000"), false).offer();
            assertThat(service.accept(player, offer)).isTrue();
            assertThat(service.cancel(player)).isTrue();
        }
        assertThat(service.ask(ana, coins("1000"), false).refusal()).isEqualTo("jobs.order.none-left");
        now.addAndGet(DAY);
        for (int i = 0; i < 5; i++) {
            assertThat(service.ask(ana, coins("1000"), true).offer()).isNotNull();
        }
        assertThat(service.ask(ana, coins("1000"), true).refusal()).isEqualTo("jobs.order.no-rerolls");
        assertThat(service.ask(ana, coins("1000"), false).offer()).as("a first offer is no re-roll").isNotNull();
    }
}
