package de.raindancer.modules.economy.model;

import org.bukkit.Material;

/**
 * What a slot reel can stop on, how often, and what three in a row pays before the house edge is
 * applied. The weights are per reel: coal comes up ten times as often as netherite.
 */
public enum SlotSymbol {
    COAL(Material.COAL, 10, 4),
    IRON(Material.IRON_INGOT, 8, 8),
    GOLD(Material.GOLD_INGOT, 6, 20),
    EMERALD(Material.EMERALD, 4, 50),
    DIAMOND(Material.DIAMOND, 3, 120),
    NETHERITE(Material.NETHERITE_INGOT, 1, 1000);

    private final Material icon;
    private final int weight;
    private final double threeOfAKind;

    SlotSymbol(Material icon, int weight, double threeOfAKind) {
        this.icon = icon;
        this.weight = weight;
        this.threeOfAKind = threeOfAKind;
    }

    public Material icon() {
        return icon;
    }

    public int weight() {
        return weight;
    }

    public double threeOfAKind() {
        return threeOfAKind;
    }

    public static int totalWeight() {
        int total = 0;
        for (SlotSymbol symbol : values()) {
            total += symbol.weight;
        }
        return total;
    }
}
