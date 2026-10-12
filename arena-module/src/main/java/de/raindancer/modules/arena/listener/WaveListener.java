package de.raindancer.modules.arena.listener;

import de.raindancer.modules.arena.ArenaServices;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SlimeSplitEvent;

import java.util.UUID;

/**
 * Mobs in the arena: they never burn in daylight or fire, drop nothing and give no experience, do not turn into
 * something else (a zombie into a drowned, a slime into smaller slimes that no wave counts), and a wave's mob goes only
 * for players of its own fight.
 */
public final class WaveListener implements IArenaListener {

    private final ArenaServices services;
    private final de.raindancer.modules.arena.rules.WaveMobRule burning = new de.raindancer.modules.arena.rules.WaveMobRule();

    public WaveListener(ArenaServices services) {
        this.services = services;
    }

    private boolean arena(World world) {
        return world != null && services.arena().isArenaWorld(world.getName());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(EntityCombustEvent event) {
        // Fire aspect, flame and lava come as the ByEntity/ByBlock subclasses; only the plain event is the sun.
        boolean caused = event instanceof org.bukkit.event.entity.EntityCombustByEntityEvent
                || event instanceof org.bukkit.event.entity.EntityCombustByBlockEvent;
        if (burning.stopsBurning(event.getEntity() instanceof Player, arena(event.getEntity().getWorld()), caused)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player || !arena(dead.getWorld())) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (services.waves().isFightMob(dead)) {
            Player killer = dead.getKiller();
            services.matches().mobDied(dead.getUniqueId(), killer == null ? null : killer.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (arena(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** An evoker's vexes wither away after half a minute, as the game's own do, and never outlive a fight. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConjured(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Vex vex && arena(vex.getWorld())) {
            vex.setLimitedLifetime(true);
            vex.setLimitedLifetimeTicks(600);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent event) {
        if (arena(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        Entity mob = event.getEntity();
        if (!arena(mob.getWorld()) || event.getTarget() == null) {
            return;
        }
        if (!services.matches().opponents(mob.getUniqueId(), event.getTarget().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player here.
    }
}
