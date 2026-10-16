package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.service.ShardDropService;
import de.raindancer.modules.enchants.service.ShardMobService;
import de.raindancer.modules.enchants.service.ShardPortalService;
import de.raindancer.modules.enchants.service.ShardWorldService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;


/** Opening and using shard portals, and the shards monsters drop. Drops at LOW, before Auto Sell and Telekinesis. */
public final class ShardListener implements IEnchantsListener {

    private final ShardWorldService worlds;
    private final ShardPortalService portals;
    private final ShardDropService drops;
    private final ShardMobService mobs;

    public ShardListener(ShardWorldService worlds, ShardPortalService portals, ShardDropService drops,
                         ShardMobService mobs) {
        this.worlds = worlds;
        this.portals = portals;
        this.drops = drops;
        this.mobs = mobs;
    }

    /** Nothing harmless lives in the shard world; now and then a warden comes instead of a monster. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrival(CreatureSpawnEvent event) {
        if (mobs.refused(event.getEntity(), event.getSpawnReason())
                || mobs.wardenInstead(event.getEntity(), event.getSpawnReason())) {
            event.setCancelled(true);
        }
    }

    /** After everybody else had their say about the spawn: only creatures that really come get a kind. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        mobs.spawned(event.getEntity(), event.getSpawnReason());
    }

    /** A chunk of the shard world came back: its always-angry creatures hunt again. */
    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        event.getEntities().forEach(entity -> {
            if (entity instanceof LivingEntity living) {
                mobs.loaded(living);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getHand() == EquipmentSlot.HAND
                && event.getClickedBlock() != null
                && portals.ignite(event.getPlayer(), event.getClickedBlock(), event.getBlockFace(), event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && portals.travel(event.getPlayer(), event.getFrom())) {
            event.setCancelled(true);
        }
    }

    /** Nothing but players goes through: no item or monster ends up in the other world. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (worlds.isShardWorld(event.getFrom().getWorld()) || portals.inOurPortal(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    /** A portal lit with fire where one of ours once stood is an ordinary nether portal. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLit(PortalCreateEvent event) {
        if (event.getReason() == PortalCreateEvent.CreateReason.FIRE) {
            event.getBlocks().forEach(state -> portals.forget(state.getBlock()));
        }
    }

    /** A creeper in the shard world blows up far bigger than anywhere else. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlast(org.bukkit.event.entity.ExplosionPrimeEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Creeper creeper && worlds.isShardWorld(creeper.getWorld())) {
            event.setRadius(de.raindancer.modules.enchants.rules.ShardBlast.radius(event.getRadius(),
                    ShardMobService.tierOf(creeper)));
        }
    }

    /** A shard creature hits harder the higher its kind: whatever it deals, itself or by what it shot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        org.bukkit.entity.Entity source = event.getDamager();
        if (source instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            source = shooter;
        }
        if (source instanceof org.bukkit.entity.Mob mob) {
            ShardMobService.tierOf(mob).ifPresent(tier -> event.setDamage(event.getDamage() * tier.damageFactor()));
        }
    }

    /** After every other plugin had its say: what the hit does to a tyrant, or with a tyrant's arrow. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurtDone(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.LivingEntity victim && worlds.isShardWorld(victim.getWorld())) {
            mobs.hurt(victim, event.getFinalDamage(), event.getDamager());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && !(event instanceof PlayerDeathEvent)) {
            drops.died(event.getEntity(), killer, event.getDrops());
        }
    }
}
