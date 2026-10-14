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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a target gone to another world is no longer chased — and asking does not throw")
    void otherWorld() {
        org.bukkit.World here = org.mockito.Mockito.mock(org.bukkit.World.class);
        org.bukkit.World there = org.mockito.Mockito.mock(org.bukkit.World.class);

        org.assertj.core.api.Assertions.assertThat(ShardMobService.stillChasing(
                new org.bukkit.Location(here, 0, 64, 0), new org.bukkit.Location(there, 1, 64, 1), 32)).isFalse();
        org.assertj.core.api.Assertions.assertThat(ShardMobService.stillChasing(
                new org.bukkit.Location(here, 0, 64, 0), new org.bukkit.Location(here, 10, 64, 0), 32)).isTrue();
        org.assertj.core.api.Assertions.assertThat(ShardMobService.stillChasing(
                new org.bukkit.Location(here, 0, 64, 0), new org.bukkit.Location(here, 100, 64, 0), 32)).isFalse();
    }
}
