package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardTierDamageTest {

    @Test
    @DisplayName("every kind up hits at least 30 % harder than the one below it, the lowest as the game made it")
    void thirtyPercentALevel() {
        ShardTier[] tiers = ShardTier.values();
        assertThat(tiers[0].damageFactor()).isEqualTo(1.0);
        for (int i = 1; i < tiers.length; i++) {
            assertThat(tiers[i].damageFactor()).as(tiers[i].name())
                    .isGreaterThanOrEqualTo(tiers[i - 1].damageFactor() * 1.3 - 1e-9);
        }
    }
}
