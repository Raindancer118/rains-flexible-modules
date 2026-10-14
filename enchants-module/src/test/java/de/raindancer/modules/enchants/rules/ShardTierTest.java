package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ShardTierTest {

    @Test
    @DisplayName("most are the weakest kind, a few are tyrants")
    void rolls() {
        Map<ShardTier, Integer> seen = new EnumMap<>(ShardTier.class);
        Random random = new Random(11);
        for (int i = 0; i < 50_000; i++) {
            seen.merge(ShardTier.roll(random), 1, Integer::sum);
        }
        assertThat(seen.get(ShardTier.SHADOWED)).isBetween(29_000, 31_000);
        assertThat(seen.get(ShardTier.SHARDBORN)).isBetween(13_300, 14_700);
        assertThat(seen.get(ShardTier.CRYSTAL)).isBetween(4_500, 5_500);
        assertThat(seen.get(ShardTier.TYRANT)).isBetween(800, 1_200);
    }

    @Test
    @DisplayName("each harder kind is tougher, drops more shards and makes the rare ones likelier")
    void harderDropsMore() {
        Random random = new Random(5);
        ShardTier previous = null;
        for (ShardTier tier : ShardTier.values()) {
            for (int i = 0; i < 200; i++) {
                int shards = tier.shards(random);
                assertThat(shards).isBetween(tier.fewestShards(), tier.mostShards());
            }
            if (previous != null) {
                assertThat(tier.fewestShards()).as(tier.name()).isGreaterThanOrEqualTo(previous.mostShards());
                assertThat(tier.healthFactor()).isGreaterThan(previous.healthFactor());
                assertThat(tier.rareFactor()).isGreaterThanOrEqualTo(previous.rareFactor());
            }
            previous = tier;
        }
        assertThat(ShardTier.SHADOWED.shards(random)).isEqualTo(1);
    }

    @Test
    @DisplayName("a tier is read back by its key; anything else is the weakest")
    void byKey() {
        assertThat(ShardTier.of("tyrant")).isEqualTo(ShardTier.TYRANT);
        assertThat(ShardTier.of(null)).isEqualTo(ShardTier.SHADOWED);
        assertThat(ShardTier.of("nonsense")).isEqualTo(ShardTier.SHADOWED);
    }

    @Test
    @DisplayName("the whole name is in the tier's colour, the creature's own name inside it")
    void coloured() {
        for (ShardTier tier : ShardTier.values()) {
            assertThat(tier.title()).as(tier.name()).startsWith("<").endsWith("<name>");
        }
    }
}
