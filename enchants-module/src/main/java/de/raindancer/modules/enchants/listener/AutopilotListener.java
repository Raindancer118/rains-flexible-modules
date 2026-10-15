package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.service.AutopilotService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Autopilot's safety: no take-off mid-fight, and nobody left a spectator by a quit or a crash. */
public final class AutopilotListener implements IEnchantsListener {

    private final AutopilotService autopilot;

    public AutopilotListener(AutopilotService autopilot) {
        this.autopilot = autopilot;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent event) {
        boolean byPlayer = event.getDamager() instanceof Player
                || event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player;
        if (byPlayer && event.getEntity() instanceof Player victim) {
            autopilot.fought(victim.getUniqueId());
        }
    }

    /** A spectator in flight goes nowhere else: not by the spectator menu, a command, or anybody's teleport. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (autopilot.flying(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Nor looks into anything a spectator could open. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (autopilot.flying(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (autopilot.flying(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (autopilot.flying(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** A and D switch the camera in flight. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(org.bukkit.event.player.PlayerInputEvent event) {
        autopilot.input(event.getPlayer(), event.getInput());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        autopilot.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        autopilot.join(event.getPlayer());
    }
}
