package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** The shop across the network on one bus: "smp" keeps the money and the shop, "oneblock" only asks. */
class ShopWireTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final NetworkCalls smp = new NetworkCalls(hub.bus("smp"));
    private final NetworkCalls island = new NetworkCalls(hub.bus("oneblock"));
    private final List<String> asked = new ArrayList<>();
    private final AtomicBoolean isBank = new AtomicBoolean(true);

    private final ShopDesk desk = new ShopDesk() {
        @Override
        public List<Line> catalogue(UUID player) {
            asked.add("catalogue " + player);
            return List.of(new Line("COBBLESTONE", "building_blocks", true, true, "<gold>$1", "<gold>$0.50", 0),
                    new Line("PIG_SPAWN_EGG", "eggs", true, false, "<gold>$100", "", 12));
        }

        @Override
        public Quote quote(UUID player, String material, List<Integer> buyCounts, List<Integer> sellCounts) {
            asked.add("quote " + material + " " + buyCounts + " " + sellCounts);
            return new Quote(true, true, buyCounts.stream().map(count -> new Price(count, "$" + count, count * 100L)).toList(),
                    sellCounts.stream().map(count -> new Price(count, "$" + count / 2, count * 50L)).toList(),
                    List.of("<green>5% off for you"));
        }

        @Override
        public Settled buy(UUID player, String material, int count, long most) {
            asked.add("buy " + material + " " + count + " " + most);
            return most >= count * 100L ? new Settled(true, "economy.shop.bought",
                    List.of("count", String.valueOf(count), "item", "Cobblestone", "amount", "<gold>$" + count))
                    : Settled.refused("economy.shop.price-moved", "price", "<gold>$" + count);
        }

        @Override
        public Settled sell(UUID player, String material, int count) {
            asked.add("sell " + material + " " + count);
            return new Settled(true, "economy.shop.sold", List.of("what", count + " × Cobblestone", "amount", "<gold>$1"));
        }
    };

    private final ShopWire.Client client = new ShopWire.Client(island, () -> Optional.of("smp"), Duration.ofSeconds(3));

    {
        ShopWire.serve(smp, desk, isBank::get, Runnable::run);
    }

    @AfterEach
    void close() {
        smp.close();
        island.close();
    }

    @Test
    @DisplayName("the price list comes across whole, with the bank's own rendering of money")
    void catalogue() {
        Optional<List<ShopDesk.Line>> lines = client.catalogue(ALEX).join();

        assertThat(lines).hasValueSatisfying(all -> assertThat(all).containsExactly(
                new ShopDesk.Line("COBBLESTONE", "building_blocks", true, true, "<gold>$1", "<gold>$0.50", 0),
                new ShopDesk.Line("PIG_SPAWN_EGG", "eggs", true, false, "<gold>$100", "", 12)));
        assertThat(asked).containsExactly("catalogue " + ALEX);
    }

    @Test
    @DisplayName("a quote for the amounts asked, buying and selling, and its notes")
    void quote() {
        Optional<ShopDesk.Quote> quote = client.quote(ALEX, "COBBLESTONE", List.of(1, 64), List.of(10)).join();

        assertThat(quote).hasValueSatisfying(one -> {
            assertThat(one.buys()).containsExactly(new ShopDesk.Price(1, "$1", 100), new ShopDesk.Price(64, "$64", 6400));
            assertThat(one.sells()).containsExactly(new ShopDesk.Price(10, "$5", 500));
            assertThat(one.notes()).containsExactly("<green>5% off for you");
        });
    }

    @Test
    @DisplayName("a purchase carries the most the player agreed to; the answer is a line to say")
    void buy() {
        assertThat(client.buy(ALEX, "COBBLESTONE", 3, 300).join()).hasValueSatisfying(settled -> {
            assertThat(settled.ok()).isTrue();
            assertThat(settled.key()).isEqualTo("economy.shop.bought");
            assertThat(settled.values()).containsExactly("count", "3", "item", "Cobblestone", "amount", "<gold>$3");
        });
        assertThat(client.buy(ALEX, "COBBLESTONE", 3, 299).join()).hasValueSatisfying(settled ->
                assertThat(settled.ok()).isFalse());
        assertThat(asked).containsExactly("buy COBBLESTONE 3 300", "buy COBBLESTONE 3 299");
    }

    @Test
    @DisplayName("a sale is booked by the bank")
    void sell() {
        assertThat(client.sell(ALEX, "COBBLESTONE", 5).join()).hasValueSatisfying(settled ->
                assertThat(settled.key()).isEqualTo("economy.shop.sold"));
    }

    @Test
    @DisplayName("a server that no longer keeps the money answers nothing, and nothing is booked")
    void notTheBank() {
        isBank.set(false);

        assertThat(client.buy(ALEX, "COBBLESTONE", 1, 100).join()).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("with no bank to ask there is no answer, not a hang")
    void noBank() {
        ShopWire.Client lonely = new ShopWire.Client(island, Optional::empty, Duration.ofSeconds(1));

        assertThat(lonely.catalogue(ALEX).join()).isEmpty();
        assertThat(lonely.sell(ALEX, "COBBLESTONE", 1).join()).isEmpty();
    }
}
