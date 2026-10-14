package de.raindancer.modules.enchants.service;

import de.raindancer.modules.enchants.rules.ShardTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The creatures of the shard world, every kind of them: each that spawns by itself becomes one of the {@link ShardTier} kinds —
 * named, and tougher the rarer it is. The kind is kept on the monster, so its drop knows what it was.
 */
public final class ShardMobService {

    public static final NamespacedKey TIER = new NamespacedKey("rainsenchants", "shard_tier");
    private static final NamespacedKey BOOST = new NamespacedKey("rainsenchants", "shard_tier_boost");

    private final ShardWorldService worlds;

    public ShardMobService(ShardWorldService worlds) {
        this.worlds = worlds;
    }

    public void spawned(LivingEntity entity, CreatureSpawnEvent.SpawnReason reason) {
        if (!(entity instanceof Mob mob) || !bySelf(reason) || !worlds.isShardWorld(entity.getWorld())) {
            return;
        }
        ShardTier tier = ShardTier.roll(ThreadLocalRandom.current());
        mob.getPersistentDataContainer().set(TIER, PersistentDataType.STRING, tier.key());
        mob.customName(MiniMessage.miniMessage().deserialize(tier.title() + " ")
                .append(Component.translatable(mob.getType().translationKey())));
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
