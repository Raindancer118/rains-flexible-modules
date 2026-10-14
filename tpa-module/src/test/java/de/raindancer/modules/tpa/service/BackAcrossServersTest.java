package de.raindancer.modules.tpa.service;

import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Returns;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.core.world.teleport.Waypoint;
import de.raindancer.modules.tpa.TpaSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /back to where somebody was on another server of the network. */
class BackAcrossServersTest {

    private final UUID id = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final Messages messages = mock(Messages.class);
    private final Travel travel = mock(Travel.class);
    private final Returns returns = new Returns();
    private final List<String> hoppedTo = new ArrayList<>();
    private final Waypoint died = new Waypoint("world", 10, 64, 20, 0, 0, Waypoint.Cause.DEATH, 0);

    BackAcrossServersTest() {
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(anyString())).thenReturn(false);
        when(travel.cameFrom()).thenReturn(returns);
        returns.restore(id, new Returns.Elsewhere("smp", died));
    }

    private BackService service(CompletableFuture<Boolean> hopped) {
        BackService back = new BackService(travel, messages, TpaSettings.DEFAULTS.withBackCooldownSeconds(60), (java.util.function.LongSupplier) null);
        back.acrossServers((who, server, where) -> {
            hoppedTo.add(server + " " + where.x() + "," + where.z());
            return hopped;
        }, null);
        return back;
    }

    private TravelWatcher watcher() {
        ArgumentCaptor<TravelWatcher> captor = ArgumentCaptor.forClass(TravelWatcher.class);
        verify(travel).goElsewhere(eq(player), any(Trip.class), captor.capture(), any());
        verify(travel, never()).go(any(), any(), any(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("a place on another server: waited out here, then a hop there that lands on it")
    void goesThere() {
        BackService back = service(CompletableFuture.completedFuture(true));
        assertThat(back.go(player)).isTrue();
        watcher().arrived(player, mock(Location.class), Trip.to("x"));

        assertThat(hoppedTo).containsExactly("smp 10.0,20.0");
        assertThat(returns.anywhere(id)).as("used up").isEmpty();
        assertThat(back.isReady(player)).as("the wait started").isFalse();
    }

    @Test
    @DisplayName("a hop that did not move them puts the place back on its server and starts no wait")
    void hopFailed() {
        BackService back = service(CompletableFuture.completedFuture(false));
        back.go(player);
        watcher().arrived(player, mock(Location.class), Trip.to("x"));

        assertThat(returns.anywhere(id)).get().extracting(Returns.Elsewhere::server).isEqualTo("smp");
        assertThat(back.isReady(player)).isTrue();
    }

    @Test
    @DisplayName("cancelled during the wait: the place is kept, on its server")
    void cancelled() {
        BackService back = service(new CompletableFuture<>());
        back.go(player);
        watcher().cancelled(player, TravelReason.MOVED, Trip.to("x"));

        assertThat(returns.anywhere(id)).get().extracting(Returns.Elsewhere::server).isEqualTo("smp");
        assertThat(hoppedTo).isEmpty();
    }

    @Test
    @DisplayName("no way to other servers here: refused, the place kept")
    void noHop() {
        BackService back = new BackService(travel, messages, TpaSettings.DEFAULTS, (java.util.function.LongSupplier) null);
        assertThat(back.go(player)).isFalse();
        verify(messages).send(eq(player), eq("tpa.back-other-server-unavailable"), any(Object[].class));
        assertThat(returns.anywhere(id)).isPresent();
    }

    @Test
    @DisplayName("while the hop is under way, a second /back is refused")
    void noSecondDuringHop() {
        BackService back = service(new CompletableFuture<>());
        back.go(player);
        watcher().arrived(player, mock(Location.class), Trip.to("x"));
        returns.restore(id, new Returns.Elsewhere("smp", died));

        assertThat(back.go(player)).isFalse();
        verify(messages).send(eq(player), eq("tpa.already-travelling"), any(Object[].class));
    }
}
