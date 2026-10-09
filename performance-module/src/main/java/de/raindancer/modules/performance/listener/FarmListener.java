package de.raindancer.modules.performance.listener;

import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.modules.performance.PerformanceServices;
import de.raindancer.modules.performance.PerformanceSettings;
import de.raindancer.modules.performance.model.Crowd;
import de.raindancer.modules.performance.rules.FarmRule;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Stops a farm from growing past the limits. Hooked on the spawn rather than on {@code EntityBreedEvent}:
 * a cancelled breed event leaves both parents in love, so they try again every tick, while a cancelled
 * spawn lets breeding finish normally with no baby.
 */
public final class FarmListener implements IPerformanceListener {

    private final PerformanceServices services;
    private final Cooldowns<UUID> told = new Cooldowns<>();

    public FarmListener(PerformanceServices services) {
        this.services = services;
        told.every(Duration.ofMinutes(1));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        PerformanceSettings settings = services.settings().get();
        FarmRule rule = services.rule();
        if (!rule.limits(event.getSpawnReason(), settings)) {
            return;
        }
        LivingEntity born = event.getEntity();
        Location where = event.getLocation();
        Crowd near = services.counter().around(where, born.getType(), settings.radius());
        FarmRule.Verdict verdict = rule.judge(event.getSpawnReason(), near, settings);
        if (verdict == FarmRule.Verdict.ALLOW) {
            return;
        }
        event.setCancelled(true);
        if (settings.tellPlayers()) {
            tellNearby(where, born, verdict, settings);
        }
    }

    private void tellNearby(Location where, LivingEntity born, FarmRule.Verdict verdict, PerformanceSettings settings) {
        String kind = born.getType().getKey().getKey().replace('_', ' ').toLowerCase(Locale.ROOT);
        int limit = verdict == FarmRule.Verdict.TOO_MANY_OF_KIND ? settings.mostOfOneKind() : settings.mostAnimals();
        String key = verdict == FarmRule.Verdict.TOO_MANY_OF_KIND ? "performance.full-of-kind" : "performance.full";
        double reach = settings.radius() * 2.0;
        for (Player player : where.getNearbyPlayers(reach)) {
            if (told.tryUse(player.getUniqueId())) {
                player.sendActionBar(services.messages().get(key, "kind", kind, "limit", limit));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        told.forget(player);
    }
}
