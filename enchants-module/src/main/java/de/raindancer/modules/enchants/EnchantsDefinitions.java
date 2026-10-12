package de.raindancer.modules.enchants;

import de.raindancer.core.content.enchant.EnchantDef;
import de.raindancer.core.content.enchant.Gear;
import de.raindancer.core.content.enchant.RainsEnchants;

import java.util.List;

/** The four enchantments, as the registry is told about them at bootstrap. */
public final class EnchantsDefinitions {

    private EnchantsDefinitions() {
    }

    public static List<EnchantDef> all() {
        return List.of(
                EnchantDef.builder(RainsEnchants.VEINMINER).name("<gold>Veinminer").maxLevel(1)
                        .gear(Gear.VEIN_TOOLS).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.NIGHT_VISION).name("<aqua>Night Vision").maxLevel(1)
                        .gear(Gear.HELMET).anvilCost(4).build(),
                EnchantDef.builder(RainsEnchants.FLIGHT).name("<light_purple>Flight").maxLevel(5)
                        .gear(Gear.CHESTPLATE).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.DOUBLE_JUMP).name("<green>Double Jump").maxLevel(3)
                        .gear(Gear.BOOTS).anvilCost(5).build());
    }
}
