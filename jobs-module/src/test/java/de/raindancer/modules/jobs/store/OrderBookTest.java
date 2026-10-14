package de.raindancer.modules.jobs.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class OrderBookTest {

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

    @Test
    @DisplayName("a ledger with and without an order is written and read back")
    void roundTrip() {
        OrderBook book = servers.orders("smp");
        assertThat(book.putNow(ana, ledger(5, Order.State.OPEN))).isTrue();
        assertThat(servers.orders("smp").of(ana)).contains(ledger(5, Order.State.OPEN));
        OrderBook.Ledger none = new OrderBook.Ledger("2026-10-10", 1, 1, true, null);
        assertThat(book.putNow(ana, none)).isTrue();
        assertThat(servers.orders("smp").of(ana)).contains(none);
    }

    @Test
    @DisplayName("progress counted on two servers is added up; an order paid on one is not paid on the other")
    void bothCountPaidOnce() {
        OrderBook smp = servers.orders("smp");
        OrderBook arena = servers.orders("arena");
        smp.putNow(ana, ledger(0, Order.State.OPEN));
        servers.settle();
        arena.of(ana);
        smp.put(ana, ledger(6, Order.State.OPEN));
        arena.put(ana, ledger(7, Order.State.OPEN));
        smp.flush();
        arena.flush();
        servers.settle();
        assertThat(smp.of(ana).orElseThrow().order().progress()).isEqualTo(13);

        OrderBook.Ledger seen = arena.of(ana).orElseThrow();
        assertThat(smp.putNow(ana, smp.of(ana).orElseThrow().with(smp.of(ana).orElseThrow().order().in(Order.State.PAID)))).isTrue();
        assertThat(arena.putNow(ana, seen, ledger(40, Order.State.PAID))).as("already paid").isFalse();
    }

    @Test
    @DisplayName("a server that decided to pay on an open order is refused once the other server's payment reached it")
    void decidedOnOpenButPaidMeanwhile() {
        OrderBook smp = servers.orders("smp");
        OrderBook arena = servers.orders("arena");
        smp.putNow(ana, ledger(40, Order.State.OPEN));
        servers.settle();
        OrderBook.Ledger seenOpen = arena.of(ana).orElseThrow();
        assertThat(smp.putNow(ana, smp.of(ana).orElseThrow().with(smp.of(ana).orElseThrow().order().in(Order.State.PAID)))).isTrue();
        servers.settle();
        arena.refresh(ana);
        assertThat(arena.putNow(ana, seenOpen, seenOpen.with(seenOpen.order().in(Order.State.PAID))))
                .as("paid elsewhere since it looked").isFalse();
    }

    @Test
    @DisplayName("what is due is found in the database: out of time, or refused its payment")
    void actionable() {
        OrderBook smp = servers.orders("smp");
        smp.putNow(ana, ledger(5, Order.State.OPEN));
        assertThat(servers.orders("arena").actionable(5_000)).isEmpty();
        assertThat(servers.orders("arena").actionable(9_000)).containsOnlyKeys(ana);
        smp.putNow(ana, ledger(5, Order.State.FAILED));
        assertThat(servers.orders("arena").actionable(9_000)).isEmpty();
    }

    @Test
    @DisplayName("taking an order on a stale view is refused: the ledger it was made from has moved on")
    void staleReplacement() {
        OrderBook smp = servers.orders("smp");
        OrderBook arena = servers.orders("arena");
        smp.putNow(ana, new OrderBook.Ledger("2026-10-10", 0, 0, true, null));
        servers.settle();
        arena.of(ana);
        assertThat(smp.putNow(ana, ledger(0, Order.State.OPEN))).isTrue();
        assertThat(arena.putNow(ana, new OrderBook.Ledger("2026-10-10", 1, 0, false,
                new Order("wardens", "2 wardens", 2, Money.of(9), 1, 2, 0, Order.State.OPEN)))).isFalse();
        assertThat(arena.of(ana).orElseThrow().order().work()).isEqualTo("zombies");
    }
}
