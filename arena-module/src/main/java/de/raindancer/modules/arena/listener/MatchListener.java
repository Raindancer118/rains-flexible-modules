package de.raindancer.modules.arena.listener;

import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Fight;
import de.raindancer.modules.arena.rules.SumoRule;
import de.raindancer.modules.arena.util.Builders;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;
import java.util.UUID;

/**
 * What a fight needs from the game: a blow that would kill is a defeat instead of a death, nobody moves through a
 * countdown, fighters stay on their pit's floor and nobody else walks onto it, a sumo ring-out is out, and leaving
 * the server is leaving the fight.
 */
public final class MatchListener implements IArenaListener {

    private final ArenaServices services;
    private final SumoRule sumo = new SumoRule();

    public MatchListener(ArenaServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!services.matches().inFight(id)) {
            return;
        }
        if (!services.matches().isFighting(id) || services.matches().isFrozen(id)) {
            event.setCancelled(true);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            services.matches().beaten(player, null);
            return;
        }
        if (services.matches().isHarmless(id) && event instanceof EntityDamageByEntityEvent) {
            // Sumo: the push stays, the harm goes.
            event.setDamage(0);
            return;
        }
        boolean holdingTotem = player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        if (event.getFinalDamage() >= player.getHealth() && !holdingTotem) {
            event.setCancelled(true);
            services.matches().beaten(player, attacker(event).orElse(null));
        }
    }

    private static Optional<UUID> attacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return Optional.empty();
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity entity ? Optional.of(entity.getUniqueId()) : Optional.empty();
        }
        return Optional.of(damager.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() && !event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Optional<Fight> fight = services.matches().fightOf(id);
        Location to = event.getTo();
        if (fight.isEmpty()) {
            keepOffBusyFloors(event, player, to);
            return;
        }
        if (services.matches().isFrozen(id)) {
            Location from = event.getFrom().clone();
            from.setYaw(to.getYaw());
            from.setPitch(to.getPitch());
            event.setTo(from);
            return;
        }
        Box floor = fight.get().pit().floor();
        if (floor == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (fight.get().spec().mode() == Mode.SUMO && services.matches().isFighting(id)) {
            double dx = to.getX() - floor.centreX();
            double dz = to.getZ() - floor.centreZ();
            if (sumo.isOut(dx, dz, floor.minY() + 1 - to.getY(), services.config().sumoRadiusClamped())) {
                services.matches().beaten(player, null);
                return;
            }
        }
        if (!floor.contains(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
            event.setTo(event.getFrom());
        }
    }

    /** Somebody not in a fight walking onto the floor of one that is running is stopped at its edge. */
    private void keepOffBusyFloors(PlayerMoveEvent event, Player player, Location to) {
        if (Builders.isBuilding(player) || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        for (Fight running : services.matches().fights()) {
            Box floor = running.pit().floor();
            if (floor != null && floor.contains(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())
                    && !floor.contains(event.getFrom().getWorld().getName(), event.getFrom().getX(),
                    event.getFrom().getY(), event.getFrom().getZ())) {
                event.setTo(event.getFrom());
                return;
            }
        }
    }

    /** A pearl or a chorus fruit cannot take a fighter off their floor. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && event.getCause() != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            return;
        }
        services.matches().fightOf(event.getPlayer().getUniqueId()).map(fight -> fight.pit().floor()).ifPresent(floor -> {
            Location to = event.getTo();
            if (!floor.contains(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        services.matches().left(id);
        services.matches().stopWatching(id);
    }

    @Override
    public void forget(UUID player) {
        services.queue().forget(player);
        services.invites().forget(player);
    }

    /**
     * A boat item used on water in a naval battle puts the boat down. Adventure mode — what fighters play in — does
     * not let the game do it, so the spare boats in the naval kit did nothing.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBoatPlaced(org.bukkit.event.player.PlayerInteractEvent event) {
        org.bukkit.inventory.ItemStack held = event.getItem();
        if (held == null || !(held.getType().name().endsWith("_BOAT") || held.getType().name().endsWith("_RAFT"))
                || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<Fight> fight = services.matches().fightOf(player.getUniqueId());
        if (fight.isEmpty() || fight.get().spec().mode() != Mode.NAVAL || player.isInsideVehicle()) {
            return;
        }
        org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(5, org.bukkit.FluidCollisionMode.ALWAYS);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlock().getType() != Material.WATER) {
            return;
        }
        event.setCancelled(true);
        Location at = hit.getHitPosition().toLocation(player.getWorld());
        at.setYaw(player.getLocation().getYaw());
        player.getWorld().spawn(at, org.bukkit.entity.boat.OakBoat.class);
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
    }

    /** A rider in mounted combat stays on the pig until they are out or the fight is over. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getDismounted() instanceof org.bukkit.entity.Pig)) {
            return;
        }
        Optional<Fight> fight = services.matches().fightOf(player.getUniqueId());
        if (fight.isEmpty() || fight.get().spec().mode() != Mode.MOUNTED) {
            return;
        }
        boolean still;
        synchronized (fight.get()) {
            still = fight.get().state() != Fight.State.OVER && fight.get().isStanding(player.getUniqueId());
        }
        if (still && event.isCancellable()) {
            event.setCancelled(true);
        }
    }
}
