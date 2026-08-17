package de.raindancer.modules.chained.listener;

import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import de.raindancer.modules.chained.ChainedServices;
import de.raindancer.modules.chained.model.ChainPair;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The invisible wall: refuses a move that would pull a chained pair further apart than their run
 * allows.
 *
 * <h2>Why both events</h2>
 * {@link PlayerMoveEvent} never fires for a passenger — a boat, a horse or a Happy Ghast under a
 * player moves and the passenger's position moves with it, but only {@link VehicleMoveEvent} reports
 * it. Without handling it too, riding along would be an unenforced way through the wall, the same
 * gap {@code claims-module}'s {@code MovementListener} found and fixed for claim borders.
 *
 * <h2>Cancelling and resyncing</h2>
 * Cancelling {@link PlayerMoveEvent} snaps the server's position back to {@code from}, but the
 * client's own predicted trajectory keeps going — costing nothing while standing still, but leaving
 * somebody who was airborne (gliding, falling, flying) stuck hovering at the wall, unable to move at
 * all, because the client and server never agree again. An explicit teleport back to {@code from}
 * clears it. See {@code claims-module}'s {@code MovementListener.resyncIfAirborne}, which this
 * mirrors.
 */
public final class ChainMovementListener implements IChainedListener {

    private final ChainedServices services;
    private final Cooldowns<UUID> refusalMessages = new Cooldowns<>();

    public ChainMovementListener(ChainedServices services) {
        this.services = services;
        refreshCooldown();
    }

    /** Picks up a settings change to the wait between refusal messages. */
    public void refreshCooldown() {
        refusalMessages.every(Duration.ofSeconds(services.config().warningCooldown()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        if (wouldExceed(player, event.getFrom(), to)) {
            event.setCancelled(true);
            resyncIfAirborne(player, event.getFrom());
        }
    }

    /**
     * Riding across a distance a player's own feet could not cross.
     *
     * <p>The event carries no cancel — Bukkit gives no way to refuse a vehicle's move, only to react
     * to it — so a refused crossing puts the vehicle straight back where it came from and kills its
     * momentum, the same outcome a cancelled {@link PlayerMoveEvent} gives a player on foot.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        for (org.bukkit.entity.Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player rider && wouldExceed(rider, from, to)) {
                event.getVehicle().teleport(from);
                event.getVehicle().setVelocity(new Vector(0, 0, 0));
                return;
            }
        }
    }

    /**
     * Whether this move should be refused, sending the throttled refusal message if so.
     *
     * <p>Only for a player in an active pair with a {@code RUNNING} (not paused, not finished, not
     * yet started) run — a paused run is one where the whole point is that neither participant is
     * around to be chained to, and a finished one has nothing left to enforce.
     */
    private boolean wouldExceed(Player player, Location from, Location to) {
        UUID id = player.getUniqueId();
        ChainPair pair = services.pairs().pairOf(id).orElse(null);
        if (pair == null) {
            return false;
        }
        Optional<SpeedrunSession> session = services.chain().sessionOf(pair);
        if (session.isEmpty() || session.get().state() != SpeedrunState.RUNNING) {
            return false;
        }
        Player partner = Bukkit.getPlayer(pair.otherOf(id));
        if (partner == null) {
            // Nothing to enforce against a partner who is not around to be measured against.
            return false;
        }
        boolean exceeds = services.distance()
                .wouldExceed(from, to, partner.getLocation(), pair.maxDistance());
        if (exceeds && refusalMessages.tryUse(id)) {
            services.messages().send(player, "chained.wall");
        }
        return exceeds;
    }

    private void resyncIfAirborne(Player player, Location from) {
        if (player.isOnGround()) {
            return;
        }
        Location safe = from.clone();
        Scheduling.entity(services.plugin(), player, () -> {
            player.teleport(safe);
            player.setVelocity(new Vector(0, 0, 0));
        });
    }

    @Override
    public void forget(UUID player) {
        refusalMessages.forget(player);
    }

    @Override
    public String describe() {
        return "the invisible wall: refusing a move that would separate a chained pair too far";
    }
}
