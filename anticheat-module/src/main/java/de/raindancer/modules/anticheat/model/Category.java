package de.raindancer.modules.anticheat.model;

import org.bukkit.Material;

/** Where a check looks. Also what a menu groups checks by. */
public enum Category {

    MOVEMENT("Movement", Material.FEATHER),
    COMBAT("Combat", Material.IRON_SWORD),
    WORLD("World", Material.GRASS_BLOCK),
    INVENTORY("Inventory", Material.CHEST),
    PACKETS("Packets", Material.REPEATER),
    CLIENT("Client", Material.NAME_TAG),
    OVERALL("Overall", Material.NETHER_STAR);

    private final String title;
    private final Material icon;

    Category(String title, Material icon) {
        this.title = title;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }
}
