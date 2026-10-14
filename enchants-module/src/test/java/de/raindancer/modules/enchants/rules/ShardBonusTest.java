package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ShardBonusTest {

    @Test
    @DisplayName("every drop is 30 to 50 % more on average, never less than it was — even a single shard")
    void more() {
        for (int base : new int[]{1, 2, 4, 8, 50}) {
            SplittableRandom random = new SplittableRandom(base);
            long total = 0;
            int rolls = 20_000;
            for (int i = 0; i < rolls; i++) {
                int shards = ShardBonus.apply(base, random);
                assertThat(shards).isBetween(base, (int) Math.ceil(base * 1.5));
                total += shards;
            }
            double average = (double) total / rolls / base;
            assertThat(average).as("base " + base).isBetween(1.29, 1.51);
        }
    }
}
