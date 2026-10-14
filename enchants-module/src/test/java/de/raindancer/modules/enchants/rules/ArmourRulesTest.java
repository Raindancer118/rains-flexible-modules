package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ArmourRulesTest {

    @Test
    @DisplayName("an Undying chestplate holds 2, 4 or 8 totems; one put in only while there is room")
    void undying() {
        assertThat(Undying.capacity(1)).isEqualTo(2);
        assertThat(Undying.capacity(2)).isEqualTo(4);
        assertThat(Undying.capacity(3)).isEqualTo(8);
        assertThat(Undying.capacity(0)).isZero();
        assertThat(Undying.takes(1, 0, 5)).as("room for two").isEqualTo(2);
        assertThat(Undying.takes(3, 6, 1)).isEqualTo(1);
        assertThat(Undying.takes(2, 4, 3)).as("full").isZero();
    }

    @Test
    @DisplayName("Fusion doubles a chestplate's armour each time, three times at most")
    void fusion() {
        assertThat(Fusion.next(0)).hasValue(1);
        assertThat(Fusion.next(2)).hasValue(3);
        assertThat(Fusion.next(3)).isEmpty();
        assertThat(Fusion.factor(0)).isEqualTo(1.0);
        assertThat(Fusion.factor(3)).isEqualTo(8.0);
    }

    @Test
    @DisplayName("against a player's hit a fused chestplate's extra armour counts half")
    void fusionInPvp() {
        // Netherite chestplate fused twice: 8 armour becomes 32, 3 toughness 12; the rest of the set adds 12 and 9.
        double fused = Fusion.pvpDamageFactor(10, 44, 21, 24, 9);
        assertThat(fused).as("even half the extra keeps the game's most protection, 80 %").isEqualTo(1.0);
        assertThat(Fusion.pvpDamageFactor(10, 20, 3, 8, 0)).as("20 armour with 8 extra counts as 16")
                .isCloseTo((1 - Offence.armourShare(10, 16, 3)) / (1 - Offence.armourShare(10, 20, 3)), within(1e-9));
        assertThat(Fusion.pvpDamageFactor(10, 20, 3, 0, 0)).isEqualTo(1.0);
    }
}
