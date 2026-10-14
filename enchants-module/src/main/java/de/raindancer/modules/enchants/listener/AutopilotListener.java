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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        autopilot.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        autopilot.join(event.getPlayer());
    }
}
