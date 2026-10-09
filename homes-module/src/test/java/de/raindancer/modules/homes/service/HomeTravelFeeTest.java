package de.raindancer.modules.homes.service;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.effect.EffectSink;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.ParticleCue;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.homes.FakeEconomy;
import de.raindancer.modules.homes.HomeSettings;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What going home costs: charged before the wait, handed back when the trip never happens. */
class HomeTravelFeeTest {

    private final UUID id = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final Messages messages = mock(Messages.class);
    private final Travel travel = mock(Travel.class);
    private World world;
    private Home home;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(id);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        Poi poi = mock(Poi.class);
        when(poi.location()).thenReturn(Optional.of(new Location(world, 1, 2, 3)));
        home = mock(Home.class);
        when(home.poi()).thenReturn(poi);
        when(home.name()).thenReturn("base");
        when(home.isIn(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        FakeEconomy.uninstall();
    }

    private HomeTravelService service(String price) {
        Effects effects = new Effects(mock(EffectSink.class), () -> 0L);
        return new HomeTravelService(travel, messages, effects,
                HomeSettings.DEFAULTS.withTeleportPrice(price));
    }

    private TravelWatcher watcher() {
        ArgumentCaptor<TravelWatcher> captor = ArgumentCaptor.forClass(TravelWatcher.class);
        verify(travel).go(eq(player), any(Location.class), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("by default going home is free and needs no economy")
    void freeByDefault() {
        service("0").go(player, home);

        verify(travel).go(eq(player), any(Location.class), any(), any());
    }

    @Test
    @DisplayName("the price is taken when the trip is started")
    void charges() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");

        service("3").go(player, home);

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("7"));
        verify(travel).go(eq(player), any(Location.class), any(), any());
    }

    @Test
    @DisplayName("arriving keeps the money")
    void arrivingKeepsIt() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");
        service("3").go(player, home);

        watcher().arrived(player, new Location(world, 1, 2, 3), Trip.to("base"));

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("7"));
    }

    @Test
    @DisplayName("a trip cancelled during the wait is paid back")
    void cancelledIsRefunded() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");
        service("3").go(player, home);

        watcher().cancelled(player, TravelReason.MOVED, Trip.to("base"));

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("10"));
    }

    @Test
    @DisplayName("a trip refused on the way is paid back")
    void refusedIsRefunded() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");
        service("3").go(player, home);

        watcher().refused(player, TravelReason.NOWHERE_SAFE, Trip.to("base"));

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("10"));
    }

    @Test
    @DisplayName("the money is only ever paid back once")
    void refundedOnce() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");
        HomeTravelService service = service("3");
        service.go(player, home);
        TravelWatcher watcher = watcher();

        watcher.cancelled(player, TravelReason.MOVED, Trip.to("base"));
        watcher.refused(player, TravelReason.TELEPORT_REFUSED, Trip.to("base"));
        service.leaves(id);

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("10"));
    }

    @Test
    @DisplayName("logging out in the middle of the wait pays it back")
    void quittingMidWaitIsRefunded() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");
        HomeTravelService service = service("3");
        service.go(player, home);

        service.leaves(id);

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("10"));
    }

    @Test
    @DisplayName("somebody who cannot pay stays where they are and is told")
    void cannotPay() {
        FakeEconomy bank = FakeEconomy.install().give(id, "1");

        service("3").go(player, home);

        verify(travel, never()).go(any(), any(), any(), any());
        assertThat(bank.balance(id)).isEqualTo(Fees.amount("1"));
        verify(messages).send(eq(player), eq("homes.teleport.cannot-afford"), any(Object[].class));
    }

    @Test
    @DisplayName("a price with no economy is a refusal, never a free trip")
    void noEconomy() {
        service("3").go(player, home);

        verify(travel, never()).go(any(), any(), any(), any());
        verify(messages).send(eq(player), eq("homes.teleport.no-economy"));
    }

    @Test
    @DisplayName("the bypass node skips the price")
    void bypass() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");
        when(player.hasPermission(PermissionNodes.BYPASS_FEE)).thenReturn(true);

        service("3").go(player, home);

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("10"));
        verify(travel).go(eq(player), any(Location.class), any(), any());
    }

    @Test
    @DisplayName("somebody already on their way is not charged for a second trip")
    void alreadyTravelling() {
        FakeEconomy bank = FakeEconomy.install().give(id, "10");
        when(travel.isTravelling(id)).thenReturn(true);

        service("3").go(player, home);

        assertThat(bank.balance(id)).isEqualTo(Fees.amount("10"));
        verify(travel, never()).go(any(), any(), any(), any());
    }
}
