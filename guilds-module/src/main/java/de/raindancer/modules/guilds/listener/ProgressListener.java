package de.raindancer.modules.guilds.listener;

import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.GuildsSettings;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.Set;
import java.util.UUID;

/** What members do that earns their guild experience: monsters, bosses, advancements. Minutes are a timer. */
public final class ProgressListener implements IGuildsListener {

    private static final Set<EntityType> BOSSES = Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON,
            EntityType.ELDER_GUARDIAN, EntityType.WARDEN);

    private final GuildsServices services;

    public ProgressListener(GuildsServices services) {
        this.services = services;
    }

    @Override
    public void forget(UUID player) {
        services.xp().forget(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player) {
            return;
        }
        GuildsSettings now = services.settings().get();
        if (BOSSES.contains(dead.getType())) {
            services.xp().earn(killer.getUniqueId(), now.xpPerBoss());
        } else if (dead instanceof Enemy) {
            services.xp().earn(killer.getUniqueId(), now.xpPerMonster());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) {
            return;
        }
        services.xp().earn(event.getPlayer().getUniqueId(), services.settings().get().xpPerAdvancement());
    }
}
