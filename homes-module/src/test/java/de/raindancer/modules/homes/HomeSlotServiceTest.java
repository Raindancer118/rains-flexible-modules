package de.raindancer.modules.homes;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.homes.rules.HomeSlotRule;
import de.raindancer.modules.homes.service.HomeSlotService;
import de.raindancer.modules.homes.store.BoughtSlots;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HomeSlotServiceTest {

    @TempDir
    Path dir;

    private final UUID id = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final Messages messages = mock(Messages.class);
    private BoughtSlots slots;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(id);
        slots = new BoughtSlots(dir.resolve("bought-slots.yml"));
        slots.load();
    }

    @AfterEach
    void tearDown() {
        FakeEconomy.uninstall();
    }

    private HomeSlotService service(HomeSettings settings) {
        return new HomeSlotService(slots, new HomeSlotRule(), messages, settings);
    }

    private HomeSettings priced(String price, int growth, int most) {
        return HomeSettings.DEFAULTS.withSlotPrice(price).withSlotPriceGrowthPercent(growth)
                .withMostBoughtSlots(most);
    }

    @Test
    @DisplayName("by default buying is off and needs no economy")
    void offByDefault() {
        HomeSlotService service = service(HomeSettings.DEFAULTS);
        assertThat(service.isOn()).isFalse();
        assertThat(service.canOffer(id)).isFalse();
    }

    @Test
    @DisplayName("buying takes the price, adds the slot and says so")
    void buys() {
        FakeEconomy bank = FakeEconomy.install().give(id, "100");
        HomeSlotService service = service(priced("10", 0, 0));

        assertThat(service.buy(player)).isTrue();

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("90"));
        assertThat(slots.of(id)).isEqualTo(1);
        verify(messages).send(eq(player), eq("homes.slot.bought"), any(Object[].class));
    }

    @Test
    @DisplayName("each further slot costs more when the price grows")
    void escalates() {
        FakeEconomy bank = FakeEconomy.install().give(id, "100");
        HomeSlotService service = service(priced("10", 50, 0));

        service.buy(player);
        service.buy(player);

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("75"));
        assertThat(slots.of(id)).isEqualTo(2);
        assertThat(service.nextPrice(id)).isEqualTo(Fees.amount("22.5"));
    }

    @Test
    @DisplayName("somebody who cannot afford it is told and gets nothing")
    void cannotAfford() {
        FakeEconomy bank = FakeEconomy.install().give(id, "5");
        HomeSlotService service = service(priced("10", 0, 0));

        assertThat(service.buy(player)).isFalse();

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("5"));
        assertThat(slots.of(id)).isZero();
        verify(messages).send(eq(player), eq("homes.slot.cannot-afford"), any(Object[].class));
    }

    @Test
    @DisplayName("a price with no economy is a refusal, never a free slot")
    void noEconomy() {
        HomeSlotService service = service(priced("10", 0, 0));

        assertThat(service.buy(player)).isFalse();

        assertThat(slots.of(id)).isZero();
        verify(messages).send(eq(player), eq("homes.slot.no-economy"));
    }

    @Test
    @DisplayName("the most that may be bought is honoured")
    void mostBought() {
        FakeEconomy.install().give(id, "100");
        HomeSlotService service = service(priced("10", 0, 1));

        assertThat(service.buy(player)).isTrue();
        assertThat(service.canOffer(id)).isFalse();
        assertThat(service.buy(player)).isFalse();

        assertThat(slots.of(id)).isEqualTo(1);
        verify(messages).send(eq(player), eq("homes.slot.maxed"), any(Object[].class));
    }

    @Test
    @DisplayName("when the slot cannot be written down the money goes back")
    void refundsWhenNotSaved() throws Exception {
        FakeEconomy bank = FakeEconomy.install().give(id, "100");
        Path broken = dir.resolve("folder");
        java.nio.file.Files.createDirectories(broken.resolve("x"));
        HomeSlotService service = new HomeSlotService(new BoughtSlots(broken), new HomeSlotRule(),
                messages, priced("10", 0, 0));

        assertThat(service.buy(player)).isFalse();

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("100"));
        verify(messages, never()).send(eq(player), eq("homes.slot.bought"), any(Object[].class));
    }
}
