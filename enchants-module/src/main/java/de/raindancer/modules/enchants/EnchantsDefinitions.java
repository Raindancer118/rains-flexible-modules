package de.raindancer.modules.enchants;

import de.raindancer.core.content.enchant.EnchantDef;
import de.raindancer.core.content.enchant.Gear;
import de.raindancer.core.content.enchant.RainsEnchants;

import java.util.List;

/** Rain's enchantments, as the registry is told about them at bootstrap. Auto Sell is enforced by the economy. */
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
                        .gear(Gear.BOOTS).anvilCost(5).build(),
                EnchantDef.builder(RainsEnchants.AUTO_SELL).name("<gold>Auto Sell").maxLevel(1)
                        .gear(Gear.HAND_TOOLS).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.BOOST).name("<aqua>Boost").maxLevel(3)
                        .gear(Gear.ELYTRA).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.LAUNCH).name("<red>Launch").maxLevel(3)
                        .gear(Gear.ELYTRA).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.SWIFTNESS).name("<white>Swiftness").maxLevel(2)
                        .gear(Gear.LEGGINGS).anvilCost(4).build(),
                EnchantDef.builder(RainsEnchants.HASTE).name("<yellow>Haste").maxLevel(2)
                        .gear(Gear.DIGGING_TOOLS).anvilCost(4).build(),
                EnchantDef.builder(RainsEnchants.SOULBOUND).name("<light_purple>Soulbound").maxLevel(3)
                        .gear(Gear.ANY_GEAR).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.WISDOM).name("<green>Wisdom").maxLevel(3)
                        .gear(Gear.HAND_TOOLS).anvilCost(4).build(),
                EnchantDef.builder(RainsEnchants.EXCAVATOR).name("<gold>Excavator").maxLevel(1)
                        .gear(Gear.EXCAVATING_TOOLS).anvilCost(6).exclusiveWith(RainsEnchants.VEINMINER).build(),
                EnchantDef.builder(RainsEnchants.HARVESTER).name("<green>Harvester").maxLevel(3)
                        .gear(Gear.HOE).anvilCost(4).build(),
                EnchantDef.builder(RainsEnchants.SMELTING).name("<red>Smelting").maxLevel(1)
                        .gear(Gear.VEIN_TOOLS).anvilCost(4).exclusiveWith("minecraft:silk_touch").build(),
                EnchantDef.builder(RainsEnchants.TELEKINESIS).name("<aqua>Telekinesis").maxLevel(1)
                        .gear(Gear.HAND_TOOLS).anvilCost(4).exclusiveWith(RainsEnchants.CHEST_LINK).build(),
                EnchantDef.builder(RainsEnchants.CHEST_LINK).name("<dark_purple>Chest Link").maxLevel(1)
                        .gear(Gear.DIGGING_TOOLS).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.HEAT_SEEKING).name("<red>Heat-seeking").maxLevel(3)
                        .gear(Gear.SEEKING).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.THOR).name("<yellow>Thor").maxLevel(3)
                        .gear(Gear.THUNDER).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.VOLLEY).name("<aqua>Volley").maxLevel(2)
                        .gear(Gear.TRIDENT).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.LIFESTEAL).name("<dark_red>Lifesteal").maxLevel(3)
                        .gear(Gear.MELEE).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.EXECUTIONER).name("<dark_red>Executioner").maxLevel(3)
                        .gear(Gear.MELEE).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.BLEED).name("<red>Bleed").maxLevel(3)
                        .gear(Gear.MELEE).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.VENOM).name("<dark_green>Venom").maxLevel(3)
                        .gear(Gear.MELEE).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.CLEAVE).name("<gold>Cleave").maxLevel(3)
                        .gear(Gear.AXES).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.ARMOR_PIERCE).name("<gray>Armor Pierce").maxLevel(3)
                        .gear(Gear.MELEE).anvilCost(6).build(),
                EnchantDef.builder(RainsEnchants.BERSERK).name("<red>Berserk").maxLevel(3)
                        .gear(Gear.MELEE).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.OVERPOWER).name("<dark_purple>Overpower").maxLevel(5)
                        .gear(Gear.MELEE).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.UNDYING).name("<yellow>Undying").maxLevel(3)
                        .gear(Gear.CHESTPLATE).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.FUSION).name("<aqua>Fusion").maxLevel(1)
                        .gear(Gear.CHESTPLATE).anvilCost(8).build(),
                EnchantDef.builder(RainsEnchants.AUTOPILOT).name("<aqua>Autopilot").maxLevel(1)
                        .gear(Gear.ELYTRA).anvilCost(8).build());
    }
}
