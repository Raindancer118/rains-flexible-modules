package de.raindancer.modules.enchants.rules;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRulesTest {

    @Test
    @DisplayName("looking down, the face is the floor; looking ahead, it is the wall in front")
    void excavationPlane() {
        List<int[]> floor = ToolRules.excavation(80, 0);
        assertThat(floor).hasSize(8).allMatch(offset -> offset[1] == 0);
        List<int[]> northWall = ToolRules.excavation(0, 180);
        assertThat(northWall).hasSize(8).allMatch(offset -> offset[2] == 0);
        List<int[]> eastWall = ToolRules.excavation(10, -90);
        assertThat(eastWall).hasSize(8).allMatch(offset -> offset[0] == 0);
        assertThat(ToolRules.excavation(-70, 45)).allMatch(offset -> offset[1] == 0);
    }

    @Test
    @DisplayName("a block much harder than the one mined is left standing")
    void hardness() {
        assertThat(ToolRules.excavates(1.5f, 3f)).as("ore next to stone").isTrue();
        assertThat(ToolRules.excavates(1.5f, 50f)).as("obsidian next to stone").isFalse();
        assertThat(ToolRules.excavates(1.5f, -1f)).as("bedrock").isFalse();
        assertThat(ToolRules.excavates(0.5f, 0.6f)).isTrue();
    }

    @Test
    @DisplayName("each crop is replanted from its own seed, and the field grows with the level")
    void crops() {
        assertThat(ToolRules.seedOf(Material.WHEAT)).contains(Material.WHEAT_SEEDS);
        assertThat(ToolRules.seedOf(Material.POTATOES)).contains(Material.POTATO);
        assertThat(ToolRules.seedOf(Material.NETHER_WART)).contains(Material.NETHER_WART);
        assertThat(ToolRules.seedOf(Material.STONE)).isEmpty();
        assertThat(ToolRules.harvestRadius(1)).isZero();
        assertThat(ToolRules.harvestRadius(3)).isEqualTo(2);
    }

    @Test
    @DisplayName("wisdom adds its percent per level, rounding by chance so small drops still gain")
    void wisdom() {
        assertThat(ToolRules.wisdom(10, 2, 50, new Random(1))).isEqualTo(20);
        assertThat(ToolRules.wisdom(0, 3, 50, new Random(1))).isZero();
        int total = 0;
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            total += ToolRules.wisdom(1, 1, 50, random);
        }
        assertThat(total).isBetween(1400, 1600);
    }

    @Test
    @DisplayName("Launch waits less at higher levels; Soulbound gives up a level per death")
    void cooldownsAndLevels() {
        assertThat(ToolRules.launchCooldownMillis(1, 300)).isEqualTo(300_000);
        assertThat(ToolRules.launchCooldownMillis(2, 300)).isEqualTo(200_000);
        assertThat(ToolRules.launchCooldownMillis(3, 300)).as("III: none at all").isZero();
        assertThat(ToolRules.soulboundAfterDeath(3)).isEqualTo(2);
        assertThat(ToolRules.soulboundAfterDeath(1)).isZero();
    }

    @Test
    @DisplayName("a boost steers towards the look, like a rocket, only harder")
    void boostStep() {
        double[] still = ToolRules.boostStep(new double[]{0, 0, 0}, new double[]{0, 0, 1}, 150);
        assertThat(still[2]).isGreaterThan(1.0);
        double[] cruising = {0, 0, 2.25};
        for (int tick = 0; tick < 40; tick++) {
            cruising = ToolRules.boostStep(cruising, new double[]{0, 0, 1}, 150);
        }
        assertThat(cruising[2]).as("settles above a rocket's 1.5").isBetween(2.3, 2.6);
    }
}
