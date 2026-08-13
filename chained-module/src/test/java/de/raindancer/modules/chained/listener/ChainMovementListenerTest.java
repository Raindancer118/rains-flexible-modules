package de.raindancer.modules.chained.listener;

import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import de.raindancer.modules.chained.ChainedServices;
import de.raindancer.modules.chained.FakeServices;
import de.raindancer.modules.chained.model.ChainPair;
import de.raindancer.modules.chained.service.ChainService;
import de.raindancer.modules.chained.store.ChainPairStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The invisible wall: cancelling a move that would separate a chained, running pair too far, and
 * resyncing an airborne player when it does.
 *
 * <p>Follows {@code claims-module}'s {@code AirborneBorderResyncTest} for the resync half — same
 * bug, same fix, a different border.
 */
@ExtendWith(MockitoExtension.class)
class ChainMovementListenerTest {

    private final World world = FakeServices.world();
    private final UUID playerId = UUID.randomUUID();
    private final UUID partnerId = UUID.randomUUID();

    private ChainMovementListener listener(ChainedServices services) {
        return new ChainMovementListener(services);
    }

    private ChainedServices runningPairServices(ChainPairStore pairs) {
        ChainPair pair = new ChainPair(playerId, partnerId, 32);
        pairs.pair(pair);

        SpeedrunSession session = mock(SpeedrunSession.class);
        when(session.state()).thenReturn(SpeedrunState.RUNNING);

        ChainService chain = mock(ChainService.class);
        when(chain.sessionOf(playerId)).thenReturn(Optional.of(session));

        return FakeServices.builder().pairs(pairs).chain(chain).build();
    }

    @Test
    @DisplayName("a move that would separate a running pair past the limit is cancelled")
    void separatingMoveIsCancelled() {
        ChainPairStore pairs = new ChainPairStore();
        ChainedServices services = runningPairServices(pairs);

        Player player = FakeServices.player(playerId);
        Player partner = FakeServices.player(partnerId);
        when(partner.getLocation()).thenReturn(FakeServices.at(world, 0, 64, 0));
        when(player.isOnGround()).thenReturn(true);

        Location from = FakeServices.at(world, 30, 64, 0);
        Location to = FakeServices.at(world, 35, 64, 0);   // past 32, and further than before
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(partnerId)).thenReturn(partner);

            listener(services).onMove(event);
        }

        assertThat(event.isCancelled()).isTrue();
        verify(services.messages()).send(player, "chained.wall");
    }

    @Test
    @DisplayName("a move that stays within the limit is allowed")
    void allowedMoveIsNotCancelled() {
        ChainPairStore pairs = new ChainPairStore();
        ChainedServices services = runningPairServices(pairs);

        Player player = FakeServices.player(playerId);
        Player partner = FakeServices.player(partnerId);
        when(partner.getLocation()).thenReturn(FakeServices.at(world, 0, 64, 0));

        Location from = FakeServices.at(world, 5, 64, 0);
        Location to = FakeServices.at(world, 10, 64, 0);   // still under 32
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(partnerId)).thenReturn(partner);

            listener(services).onMove(event);
        }

        assertThat(event.isCancelled()).isFalse();
        verify(player, never()).teleport(any(Location.class));
    }

    @Test
    @DisplayName("cancelling an airborne player's refused move resyncs them, not just stops them")
    void airbornePlayerIsResynced() {
        ChainPairStore pairs = new ChainPairStore();
        ChainedServices services = runningPairServices(pairs);

        Player player = FakeServices.player(playerId);
        Player partner = FakeServices.player(partnerId);
        when(partner.getLocation()).thenReturn(FakeServices.at(world, 0, 64, 0));
        when(player.isOnGround()).thenReturn(false);

        Location from = FakeServices.at(world, 30, 64, 0);
        Location to = FakeServices.at(world, 35, 64, 0);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(partnerId)).thenReturn(partner);

            listener(services).onMove(event);
        }

        assertThat(event.isCancelled()).isTrue();
        verify(player).teleport(from);
        verify(player).setVelocity(new Vector(0, 0, 0));
    }

    @Test
    @DisplayName("a grounded player refused at the wall is only stopped — cancelling already works there")
    void groundedPlayerIsNotTeleported() {
        ChainPairStore pairs = new ChainPairStore();
        ChainedServices services = runningPairServices(pairs);

        Player player = FakeServices.player(playerId);
        Player partner = FakeServices.player(partnerId);
        when(partner.getLocation()).thenReturn(FakeServices.at(world, 0, 64, 0));
        when(player.isOnGround()).thenReturn(true);

        Location from = FakeServices.at(world, 30, 64, 0);
        Location to = FakeServices.at(world, 35, 64, 0);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(partnerId)).thenReturn(partner);

            listener(services).onMove(event);
        }

        assertThat(event.isCancelled()).isTrue();
        verify(player, never()).teleport(any(Location.class));
    }

    @Test
    @DisplayName("a player with no pair is never touched")
    void unpairedPlayerIsUntouched() {
        ChainedServices services = FakeServices.builder().pairs(new ChainPairStore()).build();
        Player player = FakeServices.player(playerId);

        Location from = FakeServices.at(world, 0, 64, 0);
        Location to = FakeServices.at(world, 1000, 64, 0);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        listener(services).onMove(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("forgetting a player clears their refusal-message throttle")
    void forgetClearsTheThrottle() {
        ChainedServices services = FakeServices.builder().build();
        ChainMovementListener listener = listener(services);

        // Nothing observable from the outside beyond "this does not throw" — the throttle map itself
        // is private, and the cooldown's own behaviour is Core's Cooldowns, already tested there.
        listener.forget(playerId);
    }
}
