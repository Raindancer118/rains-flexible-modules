package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/** Ores grouped the way a player thinks of them: a deepslate diamond is a diamond. */
public enum OreKind {

    DIAMOND("Diamonds"),
    ANCIENT_DEBRIS("Ancient debris"),
    EMERALD("Emeralds"),
    GOLD("Gold"),
    NETHER_GOLD("Nether gold"),
    IRON("Iron"),
    COPPER("Copper"),
    LAPIS("Lapis"),
    REDSTONE("Redstone"),
    COAL("Coal"),
    QUARTZ("Quartz");

    private final String title;

    OreKind(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static Optional<OreKind> of(Material material) {
        return material == null ? Optional.empty() : of(material.name());
    }

    public static Optional<OreKind> of(String materialName) {
        if (materialName == null) {
            return Optional.empty();
        }
        String name = materialName.toUpperCase(Locale.ROOT);
        return Optional.ofNullable(switch (name) {
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> DIAMOND;
            case "ANCIENT_DEBRIS" -> ANCIENT_DEBRIS;
            case "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE" -> EMERALD;
            case "GOLD_ORE", "DEEPSLATE_GOLD_ORE" -> GOLD;
            case "NETHER_GOLD_ORE" -> NETHER_GOLD;
            case "IRON_ORE", "DEEPSLATE_IRON_ORE" -> IRON;
            case "COPPER_ORE", "DEEPSLATE_COPPER_ORE" -> COPPER;
            case "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE" -> LAPIS;
            case "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE" -> REDSTONE;
            case "COAL_ORE", "DEEPSLATE_COAL_ORE" -> COAL;
            case "NETHER_QUARTZ_ORE" -> QUARTZ;
            default -> null;
        });
    }

    /** Natural rock that ore hides in — what a miner digs through. */
    public static boolean isHostRock(Material material) {
        if (material == null) {
            return false;
        }
        return switch (material) {
            case STONE, DEEPSLATE, TUFF, GRANITE, DIORITE, ANDESITE, CALCITE, DRIPSTONE_BLOCK, SMOOTH_BASALT,
                 NETHERRACK, BASALT, BLACKSTONE, SOUL_SOIL, MAGMA_BLOCK, END_STONE, COBBLED_DEEPSLATE,
                 INFESTED_STONE, INFESTED_DEEPSLATE -> true;
            default -> false;
        };
    }
}
