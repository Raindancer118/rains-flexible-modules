package de.raindancer.modules.enchants.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.enchants.rules.ShardSpawnRule;
import de.raindancer.modules.enchants.rules.ShardTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Bee;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The creatures of the shard world: only hostile ones, the neutral kinds always angry ({@link ShardSpawnRule}); each
 * that spawns by itself becomes one of the {@link ShardTier} kinds —
 * named, and tougher the rarer it is. The kind is kept on the monster, so its drop knows what it was.
 */
public final class ShardMobService {

    public static final NamespacedKey TIER = new NamespacedKey("rainsenchants", "shard_tier");
    private static final NamespacedKey BOOST = new NamespacedKey("rainsenchants", "shard_tier_boost");

    /** How far an always-angry creature looks for somebody to go for. */
    private static final double HUNT_BLOCKS = 24;
    private static final int ANGER_TICKS = 400;

    private final Plugin plugin;
    private final ShardWorldService worlds;
    /** Creatures already hunting: a chunk made with its creatures announces them twice. */
    private final java.util.Set<java.util.UUID> hunting = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ShardMobService(Plugin plugin, ShardWorldService worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
    }

    /**
     * Whether a creature may come into the shard world by itself at all: only monsters and the neutral kinds made
     * to hunt. Eggs, spawners and commands are staff's and pass.
     */
    public boolean refused(LivingEntity entity, CreatureSpawnEvent.SpawnReason reason) {
        return bySelf(reason) && worlds.isShardWorld(entity.getWorld())
                && ShardSpawnRule.of(entity.getType()) == ShardSpawnRule.REFUSED;
    }

    /** A creature of the shard world came into memory (spawned, or its chunk loaded): the neutral ones start hunting. */
    public void loaded(LivingEntity entity) {
        if (entity instanceof Mob mob && tierOf(mob).isPresent() && worlds.isShardWorld(mob.getWorld())
                && ShardSpawnRule.of(mob.getType()) == ShardSpawnRule.ENRAGED && hunting.add(mob.getUniqueId())) {
            Scheduling.entityTimer(plugin, mob, 20, 20, task -> hunt(mob), () -> hunting.remove(mob.getUniqueId()));
        }
    }

    /**
     * A creature next to {@code near} as if it had come by itself — named, tiered and angry like one; refused like
     * one too. For staff and events.
     *
     * @return the creature, or empty when the shard world does not take that kind
     */
    public java.util.Optional<LivingEntity> summon(Player near, org.bukkit.entity.EntityType type) {
        // Not checked against the rule here: the spawn goes through the same refusal as one in the dark.
        Class<?> kind = type.getEntityClass();
        // Bosses never come by themselves, so they are not anybody's to call either.
        if (!worlds.isShardWorld(near.getWorld()) || !type.isAlive() || !type.isSpawnable()
                || kind == null || org.bukkit.entity.Boss.class.isAssignableFrom(kind)) {
            return java.util.Optional.empty();
        }
        // At the player's feet: a spot ahead may be rock on amplified ground, and a creature in rock suffocates.
        org.bukkit.Location at = near.getLocation();
        org.bukkit.entity.Entity made = near.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.NATURAL);
        return made instanceof LivingEntity living && made.isValid() ? java.util.Optional.of(living) : java.util.Optional.empty();
    }

    /** Whether a target is still worth chasing: in the same world (gone through a portal, it is not) and near enough. */
    static boolean stillChasing(org.bukkit.Location target, org.bukkit.Location mob, double within) {
        return target.getWorld() != null && target.getWorld().equals(mob.getWorld())
                && target.distanceSquared(mob) <= within * within;
    }

    /** Keeps an always-angry creature on the nearest player who can be hurt, angry the way its kind gets angry. */
    private static void hunt(Mob mob) {
        if (mob.getTarget() instanceof Player held && huntable(held)
                && stillChasing(held.getLocation(), mob.getLocation(), HUNT_BLOCKS * 2)) {
            return;
        }
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : mob.getLocation().getNearbyPlayers(HUNT_BLOCKS)) {
            double distance = player.getLocation().distanceSquared(mob.getLocation());
            if (huntable(player) && distance < best) {
                best = distance;
                nearest = player;
            }
        }
        if (nearest == null) {
            return;
        }
        switch (mob) {
            case Wolf wolf -> wolf.setAngry(true);
            case Bee bee -> bee.setAnger(ANGER_TICKS);
            case PigZombie piglin -> {
                piglin.setAngry(true);
                piglin.setAnger(ANGER_TICKS);
            }
            case IronGolem golem -> golem.setPlayerCreated(false);
            default -> {
            }
        }
        mob.setTarget(nearest);
    }

    private static boolean huntable(Player player) {
        return player.isValid() && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
    }

    public void spawned(LivingEntity entity, CreatureSpawnEvent.SpawnReason reason) {
        if (!(entity instanceof Mob mob) || !bySelf(reason) || !worlds.isShardWorld(entity.getWorld())) {
            return;
        }
        ShardTier tier = ShardTier.roll(ThreadLocalRandom.current());
        mob.getPersistentDataContainer().set(TIER, PersistentDataType.STRING, tier.key());
        mob.customName(MiniMessage.miniMessage().deserialize(tier.title(),
                Placeholder.component("name", Component.translatable(mob.getType().translationKey()))));
        mob.setCustomNameVisible(tier.nameShown());
        // A name alone does not keep a monster for ever (a name tag does that by other means); said anyway, so the
        // night never fills up with monsters that cannot leave.
        mob.setRemoveWhenFarAway(true);
        multiply(mob, Attribute.MAX_HEALTH, tier.healthFactor() - 1);
        add(mob, Attribute.ATTACK_DAMAGE, tier.extraDamage());
        multiply(mob, Attribute.MOVEMENT_SPEED, tier.speedFactor() - 1);
        add(mob, Attribute.ARMOR, tier.armour());
        AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            mob.setHealth(health.getValue());
        }
        if (tier == ShardTier.TYRANT) {
            mob.setGlowing(true);
        }
        loaded(mob);
    }

    /**
     * Came by itself: spawned in the dark or made with the land. Bred, hatched, spawner, egg, command and every
     * other made-to-order spawn never gets a kind, so no farm of any sort yields shards.
     */
    public static boolean bySelf(CreatureSpawnEvent.SpawnReason reason) {
        return reason == CreatureSpawnEvent.SpawnReason.NATURAL || reason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN;
    }

    /** The kind it was given, empty for one that never got one. */
    public static java.util.Optional<ShardTier> tierOf(LivingEntity entity) {
        String key = entity.getPersistentDataContainer().get(TIER, PersistentDataType.STRING);
        return key == null ? java.util.Optional.empty() : java.util.Optional.of(ShardTier.of(key));
    }

    private static void multiply(Mob mob, Attribute attribute, double by) {
        modify(mob, attribute, by, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    private static void add(Mob mob, Attribute attribute, double amount) {
        modify(mob, attribute, amount, AttributeModifier.Operation.ADD_NUMBER);
    }

    private static void modify(Mob mob, Attribute attribute, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null || amount <= 0) {
            return;
        }
        instance.removeModifier(BOOST);
        instance.addModifier(new AttributeModifier(BOOST, amount, operation, EquipmentSlotGroup.ANY));
    }
}
