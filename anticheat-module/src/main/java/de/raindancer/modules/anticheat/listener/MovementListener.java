package de.raindancer.modules.anticheat.listener;

import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.MoveSample;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import io.papermc.paper.event.player.PlayerFailMoveEvent;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Set;

/** Feeds the movement engine and opens the exemption windows vanilla physics needs. */
public final class MovementListener implements IAntiCheatListener {

    private static final Set<PotionEffectType> MOVEMENT_EFFECTS = Set.of(PotionEffectType.LEVITATION,
            PotionEffectType.SLOW_FALLING, PotionEffectType.JUMP_BOOST, PotionEffectType.SPEED,
            PotionEffectType.DOLPHINS_GRACE);

    private final AntiCheatServices services;

    public MovementListener(AntiCheatServices services) {
        this.services = services;
    }

    /** Only when the packet tap is not in: then the server's move event is all there is. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        if (track.packets.tapped) {
            return;
        }
        Location to = event.getTo();
        boolean rotated = to.getYaw() != event.getFrom().getYaw() || to.getPitch() != event.getFrom().getPitch();
        track.samples.add(new MoveSample(to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch(), true, rotated,
                claimsGround(event.getPlayer()), false, 1, System.nanoTime(), false));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInput(PlayerInputEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        Input input = event.getInput();
        synchronized (track) {
            PlayerTrack.Movement m = track.movement;
            m.forward = input.isForward();
            m.backward = input.isBackward();
            m.left = input.isLeft();
            m.right = input.isRight();
            m.jump = input.isJump();
            m.sneak = input.isSneak();
            m.sprint = input.isSprint();
            m.inputChangedMillis = track.now();
            m.inputSeen = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        Vector v = event.getVelocity();
        synchronized (track) {
            track.movement.velocities.addLast(new double[]{v.getX(), v.getY(), v.getZ(), track.now(), -100, 0, 0, 0});
            while (track.movement.velocities.size() > 6) {
                track.movement.velocities.removeFirst();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            services.tracks().of(player).exempt(PlayerTrack.Exemption.ELYTRA, 1000);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoost(PlayerElytraBoostEvent event) {
        services.tracks().of(event.getPlayer()).exempt(PlayerTrack.Exemption.ELYTRA, 3500);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        services.tracks().of(event.getPlayer()).exempt(PlayerTrack.Exemption.RIPTIDE, 3000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        services.tracks().of(event.getPlayer()).exempt(PlayerTrack.Exemption.FLIGHT, 2000);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            services.tracks().of(player).exempt(PlayerTrack.Exemption.VEHICLE, 1000);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            services.tracks().of(player).exempt(PlayerTrack.Exemption.VEHICLE, 1500);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player && MOVEMENT_EFFECTS.contains(event.getModifiedType())) {
            services.tracks().of(player).exempt(PlayerTrack.Exemption.EFFECT, 1500);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent event) {
        pushed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent event) {
        pushed(event.getBlock());
    }

    private void pushed(Block piston) {
        Location at = piston.getLocation().add(0.5, 0.5, 0.5);
        for (Entity nearby : piston.getWorld().getNearbyEntities(at, 4, 4, 4, entity -> entity instanceof Player)) {
            services.tracks().of((Player) nearby).exempt(PlayerTrack.Exemption.PISTON, 1500);
        }
    }

    /** Paper's own move checks already set the player back; the failure still counts towards a level. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFailMove(PlayerFailMoveEvent event) {
        if (event.isAllowed()) {
            return;
        }
        Player player = event.getPlayer();
        PlayerTrack track = services.tracks().of(player);
        if (track.exemption() != null) {
            return;
        }
        CheckType check = switch (event.getFailReason()) {
            case MOVED_TOO_QUICKLY -> CheckType.SPEED;
            case CLIPPED_INTO_BLOCK, MOVED_WRONGLY -> CheckType.PHASE;
            default -> null;
        };
        if (check != null && track.buffer(check, 3, 0.1).fail(0.5)) {
            services.violations().flag(player, track, Flag.of(check, 0.5,
                    "the server refused the move itself (" + event.getFailReason().name().toLowerCase(Locale.ROOT) + ")"));
        }
    }

    /** Boats fly when a client says so; they do not climb out of water on their own. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        if (!(vehicle instanceof Boat) || vehicle.getPassengers().isEmpty()
                || !(vehicle.getPassengers().getFirst() instanceof Player player)) {
            return;
        }
        PlayerTrack track = services.tracks().of(player);
        if (!services.violations().runs(track, CheckType.VEHICLE) || track.exemption() != null) {
            return;
        }
        double dy = event.getTo().getY() - event.getFrom().getY();
        Block under = event.getTo().getBlock().getRelative(0, -1, 0);
        boolean supported = event.getTo().getBlock().isLiquid() || under.isLiquid() || under.getType() == Material.BUBBLE_COLUMN
                || !under.isPassable();
        int climbing;
        synchronized (track) {
            track.movement.vehicleClimbTicks = dy > 0.05 && !supported ? track.movement.vehicleClimbTicks + 1 : 0;
            climbing = track.movement.vehicleClimbTicks;
        }
        if (climbing > 4) {
            synchronized (track) {
                track.movement.vehicleClimbTicks = 0;
            }
            var decision = services.violations().flag(player, track, Flag.of(CheckType.VEHICLE,
                    String.format(Locale.ROOT, "boat climbing %.2f a tick with nothing under it", dy)));
            if (decision.act()) {
                vehicle.eject();
                services.engine().setback(player, track);
            }
        }
    }

    @Override
    public String describe() {
        return "feeding moves to the engine and opening exemptions for knockback, elytras, pistons and effects";
    }

    /**
     * What the client says about touching the ground. Paper deprecates this for being the client's word —
     * which is exactly what an anticheat has to check against the server's own view.
     */
    @SuppressWarnings("deprecation")
    private static boolean claimsGround(org.bukkit.entity.Player player) {
        return player.isOnGround();
    }
}
