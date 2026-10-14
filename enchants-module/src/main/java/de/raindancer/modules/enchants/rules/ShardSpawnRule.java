package de.raindancer.modules.enchants.rules;

import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;

import java.util.Set;

/**
 * What may live in the shard world: monsters as they are, the neutral kinds that can be made to hunt — always
 * angry — and nothing else. Goats, pandas and dolphins are neutral too, but cannot be made to attack reliably,
 * so they stay out with the harmless ones.
 */
public enum ShardSpawnRule {
    HOSTILE, ENRAGED, REFUSED;

    private static final Set<EntityType> ALWAYS_ANGRY = Set.of(EntityType.WOLF, EntityType.BEE, EntityType.POLAR_BEAR,
            EntityType.IRON_GOLEM, EntityType.LLAMA, EntityType.TRADER_LLAMA, EntityType.ENDERMAN,
            EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN, EntityType.SPIDER, EntityType.CAVE_SPIDER);

    public static ShardSpawnRule of(EntityType type) {
        if (ALWAYS_ANGRY.contains(type)) {
            return ENRAGED;
        }
        Class<?> kind = type.getEntityClass();
        return kind != null && Enemy.class.isAssignableFrom(kind) ? HOSTILE : REFUSED;
    }
}
