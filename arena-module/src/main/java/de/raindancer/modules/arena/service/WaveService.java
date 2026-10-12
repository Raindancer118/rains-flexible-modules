package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.spawn.Swarm;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Fight;
import de.raindancer.modules.arena.rules.WavePlanRule;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Putting the waves of a mob run into its pit, keeping them there, and clearing up after.
 *
 * <p>What a wave is made of is {@link WavePlanRule}'s; where each creature lands is Core's {@link Swarm} — a ring round
 * the middle of the pit, none on top of a player. Every mob is marked with its fight, never despawns, never burns
 * (see the listener), drops nothing, and is brought back into the pit if it wanders out. Once a second each fight's
 * mobs are told to go for the nearest fighter still standing.
 */
public final class WaveService {

    private static final LogChannel log = Log.of("arena");

    private final Plugin plugin;
    private final Server server;
    private final MatchService matches;
    private final WavePlanRule plan = new WavePlanRule();
    private final de.raindancer.modules.arena.rules.WaveMobRule kinds = new de.raindancer.modules.arena.rules.WaveMobRule();
    private final NamespacedKey mark;
    private ScheduledTask herding;

    public WaveService(Plugin plugin, Server server, MatchService matches) {
        this.plugin = plugin;
        this.server = server;
        this.matches = matches;
        this.mark = new NamespacedKey(plugin, "fight");
    }

    public NamespacedKey mark() {
        return mark;
    }

    public void start() {
        herding = Scheduling.globalTimer(plugin, 20L, 20L, task -> herd());
    }

    public void stop() {
        if (herding != null) {
            herding.cancel();
        }
    }

    /** The next wave of this fight, into its pit. The caller holds the fight's lock. */
    public void nextWave(Fight fight) {
        Box floor = fight.pit().floor();
        World world = floor == null ? null : server.getWorld(floor.world());
        if (world == null || fight.spec().waves() == null) {
            return;
        }
        int wave = fight.wave() + 1;
        List<String> creatures = plan.creatures(fight.spec().waves(), wave, fight.standing().size(),
                fight.id().getMostSignificantBits() ^ wave);
        Spot centre = new Spot(world.getName(), (int) Math.floor(floor.centreX()), floor.minY() + 1,
                (int) Math.floor(floor.centreZ()));
        int radius = Math.max(Swarm.NEAREST, Math.min(Swarm.FURTHEST, Math.min(floor.width(), floor.length()) / 3));
        List<Spot> spots = Swarm.ringFor(centre, creatures, radius, fight.id().getLeastSignificantBits() ^ wave);
        Location at = new Location(world, centre.x() + 0.5, centre.y(), centre.z() + 0.5);
        Scheduling.region(plugin, at, () -> {
            Set<UUID> spawned = new HashSet<>();
            for (int index = 0; index < creatures.size() && index < spots.size(); index++) {
                spawn(world, creatures.get(index), spots.get(index), fight).ifPresent(spawned::add);
            }
            if (spawned.isEmpty()) {
                log.warn("Wave {} of fight {} spawned nothing; the run counts it as cleared.", wave, fight.id());
            }
            matches.mobsSpawned(fight, spawned);
        });
    }

    private Optional<UUID> spawn(World world, String type, Spot spot, Fight fight) {
        EntityType kind;
        try {
            kind = EntityType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        if (kind.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(kind.getEntityClass())) {
            return Optional.empty();
        }
        // Stood on the top of the floor, whatever height the ring worked out — the floor is flat sand in both maps.
        Location where = new Location(world, spot.x() + 0.5, fight.pit().floor().minY() + 1, spot.z() + 0.5);
        Entity entity = world.spawn(where, kind.getEntityClass(), spawned -> {
            spawned.getPersistentDataContainer().set(mark, PersistentDataType.STRING, fight.id().toString());
            spawned.setPersistent(false);
            if (spawned instanceof LivingEntity living) {
                living.setRemoveWhenFarAway(false);
                living.setCanPickupItems(false);
                // In the overworld a piglin starts turning; the arena cancels the turning, which leaves it shaking
                // and never attacking. Immune, it fights.
                if (living instanceof org.bukkit.entity.PiglinAbstract piglin) {
                    piglin.setImmuneToZombification(true);
                }
                if (living instanceof org.bukkit.entity.Hoglin hoglin) {
                    hoglin.setImmuneToZombification(true);
                }
                if (living.getEquipment() != null) {
                    if (living.getEquipment().getItemInMainHand().getType().isAir()) {
                        kinds.weaponFor(kind.name()).map(org.bukkit.Material::matchMaterial)
                                .ifPresent(weapon -> living.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(weapon)));
                    }
                    living.getEquipment().setHelmetDropChance(0f);
                    living.getEquipment().setChestplateDropChance(0f);
                    living.getEquipment().setLeggingsDropChance(0f);
                    living.getEquipment().setBootsDropChance(0f);
                    living.getEquipment().setItemInMainHandDropChance(0f);
                    living.getEquipment().setItemInOffHandDropChance(0f);
                }
            }
        });
        return Optional.of(entity.getUniqueId());
    }

    /** Whether this entity is a fight's mob — by its mark, so it is known even after a restart. */
    public boolean isFightMob(Entity entity) {
        return entity.getPersistentDataContainer().has(mark, PersistentDataType.STRING);
    }

    /** Every mob of the fight goes — at the end, win or lose. */
    public void clear(Fight fight) {
        Box floor = fight.pit().floor();
        World world = floor == null ? null : server.getWorld(floor.world());
        if (world == null) {
            return;
        }
        Location at = new Location(world, floor.centreX(), floor.minY(), floor.centreZ());
        Scheduling.region(plugin, at, () -> {
            for (Entity entity : world.getEntities()) {
                if (isFightMob(entity) || summoned(entity, floor)) {
                    entity.remove();
                }
            }
        });
    }

    /** A vex an evoker conjured, a slime half: hostile, in the pit, belonging to no fight — gone with the fight. */
    private static boolean summoned(Entity entity, Box floor) {
        if (!(entity instanceof Mob) || entity instanceof Player) {
            return false;
        }
        if (entity instanceof org.bukkit.entity.Vex) {
            // Vexes fly through walls and over the pit; anywhere near it, they were the fight's.
            return Box.of(floor.world(), floor.minX() - 16, floor.minY() - 8, floor.minZ() - 16,
                    floor.maxX() + 16, floor.maxY() + 24, floor.maxZ() + 16)
                    .contains(entity.getWorld().getName(), entity.getX(), entity.getY(), entity.getZ());
        }
        return floor.contains(entity.getWorld().getName(), entity.getX(), entity.getY(), entity.getZ());
    }

    /** Once a second: every fight's mobs back inside their pit, after the nearest fighter still standing. */
    private void herd() {
        for (Fight fight : matches.fights()) {
            if (fight.spec().waves() == null || fight.mobs().isEmpty()) {
                continue;
            }
            Box floor = fight.pit().floor();
            World world = floor == null ? null : server.getWorld(floor.world());
            if (world == null) {
                continue;
            }
            Location centre = new Location(world, floor.centreX(), floor.minY() + 1, floor.centreZ());
            Set<UUID> standing = fight.standing();
            Scheduling.region(plugin, centre, () -> {
                List<Player> fighters = standing.stream().map(server::getPlayer)
                        .filter(player -> player != null && player.getWorld().equals(world)).toList();
                for (UUID id : fight.mobs()) {
                    Entity entity = server.getEntity(id);
                    if (entity == null || !entity.isValid()) {
                        matches.mobDied(id, null);
                        continue;
                    }
                    if (!floor.contains(world.getName(), entity.getX(), entity.getY(), entity.getZ())) {
                        entity.teleportAsync(centre);
                    }
                    if (entity instanceof Mob mob && !fighters.isEmpty()) {
                        Player nearest = fighters.stream().min(Comparator.comparingDouble(player ->
                                player.getLocation().distanceSquared(mob.getLocation()))).orElseThrow();
                        if (mob.getTarget() == null || !standing.contains(mob.getTarget().getUniqueId())) {
                            mob.setTarget(nearest);
                        }
                    }
                }
            });
        }
    }
}
