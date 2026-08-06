package de.raindancer.modules.claims;

import de.raindancer.modules.claims.listener.MovementListener;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Riding across a claim border a player's own feet could not cross.
 *
 * <h2>The bug this closes</h2>
 * {@link org.bukkit.event.player.PlayerMoveEvent} does not fire for a passenger — only the vehicle under
 * them moves, reported by {@link VehicleMoveEvent}. {@code onMove} and {@code onTeleport} were the only
 * places running the ban/entry gate, so a player banned from a claim — or one that simply refuses them
 * entry — only had to bring a Happy Ghast, a boat or a horse along to sit inside it undisturbed.
 */
@ExtendWith(MockitoExtension.class)
class VehicleBorderTest {

    private final World world = FakeServices.world();
    private final ClaimRegistry registry = new ClaimRegistry();
    private final Land land = mock(Land.class);
    private final UUID banned = UUID.randomUUID();
    private final UUID welcome = UUID.randomUUID();

    private Claim claim() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 9, 9, 0, 20);
        Claim claim = new Claim(UUID.randomUUID(), "manor", FakeServices.WORLD, "world", shape,
                UUID.randomUUID());
        claim.ban(ClaimBan.permanent(banned, UUID.randomUUID(), "trouble"));
        return claim;
    }

    private MovementListener listener(Claim claim) {
        registry.add(claim);
        ClaimServices services = FakeServices.builder().claims(registry).land(land).build();
        return new MovementListener(services);
    }

    private Vehicle vehicleCarrying(Player... riders) {
        Vehicle vehicle = mock(Vehicle.class);
        // Lenient: the sub-block-movement test below never reaches the passenger lookup at all, which
        // is exactly the behaviour it is asserting.
        org.mockito.Mockito.lenient().when(vehicle.getPassengers())
                .thenReturn(List.<org.bukkit.entity.Entity>of(riders));
        return vehicle;
    }

    @Test
    @DisplayName("a boat/Happy Ghast carrying a banned rider is put straight back at the border")
    void aBannedRiderIsTurnedBackWithTheVehicle() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        Player rider = FakeServices.player(banned);
        Vehicle vehicle = vehicleCarrying(rider);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle).teleport(from);
        verify(vehicle).setVelocity(new Vector(0, 0, 0));
        assertThat(listener.claimOf(rider))
                .as("a reverted crossing must not be recorded as an arrival")
                .isEmpty();
    }

    @Test
    @DisplayName("one banned passenger among several turns the whole vehicle back, not just them")
    void oneBannedPassengerStopsTheWholeVehicle() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        // The welcome rider alone would be let in — stubbed so the revert below is provably about the
        // banned passenger, not a false pass from an unrelated denial.
        when(land.has(any(), any(), any(LandAction.class))).thenReturn(true);
        Player welcomeRider = FakeServices.player(welcome);
        Player bannedRider = FakeServices.player(banned);
        Vehicle vehicle = vehicleCarrying(welcomeRider, bannedRider);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle).teleport(from);
        assertThat(listener.claimOf(welcomeRider))
                .as("letting the unbanned passenger through would just move the loophole to whoever "
                        + "is sitting in the front seat")
                .isEmpty();
    }

    @Test
    @DisplayName("a rider free to enter is carried across, and the border tracker follows them")
    void aWelcomeRiderCrossesAndIsTracked() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        when(land.has(any(), any(), any(LandAction.class))).thenReturn(true);
        Player rider = FakeServices.player(welcome);
        Vehicle vehicle = vehicleCarrying(rider);

        Location from = FakeServices.at(world, -1, 5, 5);
        Location to = FakeServices.at(world, 1, 5, 5);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle, never()).teleport(any(Location.class));
        assertThat(listener.claimOf(rider)).contains(claim);
    }

    @Test
    @DisplayName("a sub-block wobble that does not cross a block boundary is not even looked up")
    void subBlockMovementIsIgnored() {
        Claim claim = claim();
        MovementListener listener = listener(claim);
        Player rider = FakeServices.player(banned);
        Vehicle vehicle = vehicleCarrying(rider);

        Location from = FakeServices.at(world, 5, 5, 5);
        Location to = from.clone().add(0.2, 0, 0.1);
        listener.onVehicleMove(new VehicleMoveEvent(vehicle, from, to));

        verify(vehicle, never()).teleport(any(Location.class));
    }
}
