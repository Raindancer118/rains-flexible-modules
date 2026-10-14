package de.raindancer.modules.enchants.rules;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardSpawnRuleTest {

    @Test
    @DisplayName("monsters come as they are")
    void hostile() {
        for (EntityType type : new EntityType[]{EntityType.ZOMBIE, EntityType.CREEPER, EntityType.SKELETON,
                EntityType.WITCH, EntityType.DROWNED, EntityType.SLIME}) {
            assertThat(ShardSpawnRule.of(type)).as(type.name()).isEqualTo(ShardSpawnRule.HOSTILE);
        }
    }

    @Test
    @DisplayName("neutral ones come, and are always out for blood")
    void enraged() {
        for (EntityType type : new EntityType[]{EntityType.WOLF, EntityType.BEE, EntityType.POLAR_BEAR,
                EntityType.IRON_GOLEM, EntityType.LLAMA, EntityType.ENDERMAN, EntityType.ZOMBIFIED_PIGLIN,
                EntityType.SPIDER}) {
            assertThat(ShardSpawnRule.of(type)).as(type.name()).isEqualTo(ShardSpawnRule.ENRAGED);
        }
    }

    @Test
    @DisplayName("nothing harmless lives there: animals, villagers, fish and bats never come by themselves")
    void passive() {
        for (EntityType type : new EntityType[]{EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN,
                EntityType.VILLAGER, EntityType.BAT, EntityType.SQUID, EntityType.COD, EntityType.AXOLOTL,
                EntityType.HORSE, EntityType.GOAT, EntityType.PANDA, EntityType.DOLPHIN}) {
            assertThat(ShardSpawnRule.of(type)).as(type.name()).isEqualTo(ShardSpawnRule.REFUSED);
        }
    }
}
