package de.raindancer.modules.enchants.rules;

import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.modules.enchants.EnchantsDefinitions;
import de.raindancer.core.content.enchant.EnchantDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ShardRulesTest {

    @Test
    @DisplayName("every enchantment can drop, the strong ones rarely — and a stronger mob makes them likelier")
    void weights() {
        for (EnchantDef def : EnchantsDefinitions.all()) {
            assertThat(ShardRules.weight(def.key())).as(def.key()).isPositive();
        }
        assertThat(ShardRules.weight(RainsEnchants.SOULBOUND)).isLessThan(ShardRules.weight(RainsEnchants.HASTE));

        Map<String, Integer> weak = new HashMap<>();
        Map<String, Integer> strong = new HashMap<>();
        Random random = new Random(3);
        for (int i = 0; i < 20_000; i++) {
            weak.merge(ShardRules.pick(random, 1), 1, Integer::sum);
            strong.merge(ShardRules.pick(random, 3), 1, Integer::sum);
        }
        assertThat(weak).containsOnlyKeys(EnchantsDefinitions.all().stream().map(EnchantDef::key).toList());
        assertThat(weak.get(RainsEnchants.HASTE)).isGreaterThan(weak.get(RainsEnchants.LAUNCH) * 3);
        assertThat(strong.get(RainsEnchants.LAUNCH)).isGreaterThan(weak.get(RainsEnchants.LAUNCH) * 2);
    }
}
