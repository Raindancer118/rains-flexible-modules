package de.raindancer.modules.claims;

import de.raindancer.modules.claims.listener.MovementListener;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.store.ClaimRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A refused border crossing while airborne — elytra flight above all — has to actually put the player
 * somewhere, not just cancel the packet.
 *
 * <h2>The bug this closes</h2>
 * Cancelling {@link PlayerMoveEvent} snaps the server's own idea of the player's position back to
 * {@code from}. On the ground that is the whole story. Gliding, the client keeps predicting forward motion
 * every tick the border keeps refusing, the two positions never agree again, and the player is left hanging
 * in the air at the line, unable to glide on, fall, or do anything else — a genuine softlock reported from a
 * live server. A plain teleport back to the already-valid {@code from} spot is what actually clears it.
 */
@ExtendWith(MockitoExtension.class)
class AirborneBorderResyncTest {

    private final World world = FakeServices.world();
    private final ClaimRegistry registry = new ClaimRegistry();
    private final UUID banned = UUID.randomUUID();

    private MovementListener listener() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 9, 9, 0, 20);
        Claim claim = new Claim(UUID.randomUUID(), "manor", FakeServices.WORLD, "world", shape,
                UUID.randomUUID());
        claim.ban(ClaimBan.permanent(banned, UUID.randomUUID(), "trouble"));
        registry.add(claim);
        ClaimServices services = FakeServices.builder().claims(registry).build();
        return new MovementListener(services);
    }

    @Test
    @DisplayName("a gliding player refused at the border is teleported back, not just stopped")
    void aGlidingPlayerIsResynced() {
        Player player = FakeServices.player(banned);
        when(player.isOnGround()).thenReturn(false);

        Location from = FakeServices.at(world, -1, 15, 5);
        Location to = FakeServices.at(world, 1, 15, 5);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        listener().onMove(event);

        assertThat(event.isCancelled()).isTrue();
        verify(player).teleport(from);
        verify(player).setVelocity(new Vector(0, 0, 0));
    }

    @Test
    @DisplayName("a grounded player refused at the border is only stopped — cancelling already works there")
    void aGroundedPlayerIsNotTeleported() {
        Player player = FakeServices.player(banned);
        when(player.isOnGround()).thenReturn(true);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        listener().onMove(event);

        assertThat(event.isCancelled()).isTrue();
        verify(player, never()).teleport(org.mockito.ArgumentMatchers.any(Location.class));
    }

    @Test
    @DisplayName("an allowed crossing is neither cancelled nor resynced, airborne or not")
    void anAllowedCrossingIsUntouched() {
        Player player = FakeServices.player(UUID.randomUUID());
        MovementListener listener = listener();

        Location from = FakeServices.at(world, 20, 100, 5);
        Location to = FakeServices.at(world, 21, 100, 5);
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);

        listener.onMove(event);

        assertThat(event.isCancelled()).isFalse();
        verify(player, never()).teleport(org.mockito.ArgumentMatchers.any(Location.class));
    }
}
