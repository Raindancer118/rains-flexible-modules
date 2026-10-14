package de.raindancer.modules.enchants.rules;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class CrystalWardenTest {

    private static int risen(int perMille, EntityType type, boolean openSky, int tries) {
        SplittableRandom random = new SplittableRandom(3);
        int risen = 0;
        for (int i = 0; i < tries; i++) {
            if (CrystalWarden.rises(random, perMille, type, openSky)) {
                risen++;
            }
        }
        return risen;
    }

    @Test
    @DisplayName("a monster coming by itself under the open sky is now and then a warden instead, per mille as set")
    void rare() {
        assertThat(risen(2, EntityType.ZOMBIE, true, 100_000)).isBetween(150, 250);
        assertThat(risen(1000, EntityType.SKELETON, true, 1_000)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("never underground, never when switched off, and a warden does not turn into another")
    void never() {
        assertThat(risen(1000, EntityType.ZOMBIE, false, 1_000)).isZero();
        assertThat(risen(0, EntityType.ZOMBIE, true, 10_000)).isZero();
        assertThat(risen(1000, EntityType.WARDEN, true, 1_000)).isZero();
    }

    @Test
    @DisplayName("a warden drops five times its kind's shards")
    void loot() {
        assertThat(CrystalWarden.shards(ShardTier.TYRANT.fewestShards())).isEqualTo(30);
    }
}
