package de.raindancer.modules.enchants;

import de.raindancer.core.content.enchant.EnchantDef;
import de.raindancer.core.content.enchant.Gear;
import de.raindancer.core.content.enchant.RainsEnchants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantsDefinitionsTest {

    private static EnchantDef def(String key) {
        return EnchantsDefinitions.all().stream().filter(each -> each.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the four enchantments are exactly the keys Core publishes for others to ask about")
    void keysMatchCore() {
        assertThat(EnchantsDefinitions.all()).extracting(EnchantDef::key).containsExactlyInAnyOrder(
                RainsEnchants.VEINMINER, RainsEnchants.NIGHT_VISION, RainsEnchants.FLIGHT, RainsEnchants.DOUBLE_JUMP);
    }

    @Test
    @DisplayName("each goes on the gear it is about, and only Veinminer and Night Vision stop at one level")
    void gearAndLevels() {
        assertThat(def(RainsEnchants.VEINMINER).gear()).isEqualTo(Gear.VEIN_TOOLS);
        assertThat(def(RainsEnchants.NIGHT_VISION).gear()).isEqualTo(Gear.HELMET);
        assertThat(def(RainsEnchants.FLIGHT).gear()).isEqualTo(Gear.CHESTPLATE);
        assertThat(def(RainsEnchants.DOUBLE_JUMP).gear()).isEqualTo(Gear.BOOTS);

        assertThat(def(RainsEnchants.VEINMINER).maxLevel()).isEqualTo(1);
        assertThat(def(RainsEnchants.NIGHT_VISION).maxLevel()).isEqualTo(1);
        assertThat(def(RainsEnchants.FLIGHT).maxLevel()).isGreaterThan(1);
        assertThat(def(RainsEnchants.DOUBLE_JUMP).maxLevel()).isGreaterThan(1);
    }
}
