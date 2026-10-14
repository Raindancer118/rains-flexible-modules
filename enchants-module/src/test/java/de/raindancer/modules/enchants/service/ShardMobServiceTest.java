package de.raindancer.modules.enchants.service;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardMobServiceTest {

    @Test
    @DisplayName("only creatures that came by themselves get a kind; no farm of any sort does")
    void bySelf() {
        assertThat(ShardMobService.bySelf(SpawnReason.NATURAL)).isTrue();
        assertThat(ShardMobService.bySelf(SpawnReason.CHUNK_GEN)).isTrue();
        for (SpawnReason made : new SpawnReason[]{SpawnReason.SPAWNER, SpawnReason.TRIAL_SPAWNER, SpawnReason.BREEDING,
                SpawnReason.EGG, SpawnReason.SPAWNER_EGG, SpawnReason.COMMAND, SpawnReason.CUSTOM,
                SpawnReason.BUILD_IRONGOLEM, SpawnReason.VILLAGE_DEFENSE, SpawnReason.SLIME_SPLIT}) {
            assertThat(ShardMobService.bySelf(made)).as(made.name()).isFalse();
        }
    }

    @Test
    @DisplayName("calling a creature is its own permission, not the one for handing out books")
    void ownPermission() {
        assertThat(de.raindancer.modules.enchants.util.PermissionNodes.SUMMON)
                .isNotEqualTo(de.raindancer.modules.enchants.util.PermissionNodes.GIVE);
        assertThat(de.raindancer.modules.enchants.util.PermissionNodes.declared())
                .extracting(org.bukkit.permissions.Permission::getName)
                .contains(de.raindancer.modules.enchants.util.PermissionNodes.SUMMON);
    }
}
