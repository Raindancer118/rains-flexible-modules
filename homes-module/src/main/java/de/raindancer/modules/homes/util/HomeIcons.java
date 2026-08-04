package de.raindancer.modules.homes.util;

import de.raindancer.modules.homes.model.Home;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;
import java.util.Locale;

/**
 * What a home shows as in a menu.
 *
 * <h2>Why a name and not a {@code Material}</h2>
 * Because a home saved on a newer server, or one where a block has since been renamed, must degrade to
 * "no icon chosen" rather than failing to load. So the choice is stored as a string and resolved here,
 * every time, with a fallback — which is also why {@link #materialFor} can never return null.
 *
 * <h2>Why the automatic icon is the world's</h2>
 * A home with no chosen block still wants to look like somewhere. Grass, netherrack or end stone tells
 * somebody at a glance which of their three homes is the nether one, which is the thing they are
 * actually scanning the page for.
 */
public final class HomeIcons {

    /**
     * What an icon may be chosen from.
     *
     * <p>Curated rather than "every block on the server": a page of every material is a page nobody
     * finds anything on, and these are the ones that mean something about a place. Kept in the old
     * plugin's order, so somebody who knew where the bed was still does.
     */
    public static final List<Material> CHOICES = List.of(
            Material.RED_BED, Material.OAK_DOOR, Material.CRAFTING_TABLE, Material.FURNACE,
            Material.CHEST, Material.ENDER_CHEST, Material.ANVIL, Material.ENCHANTING_TABLE,
            Material.BREWING_STAND, Material.CAMPFIRE, Material.TORCH, Material.LANTERN,
            Material.BOOKSHELF, Material.JUKEBOX, Material.NOTE_BLOCK, Material.BEACON,
            Material.GRASS_BLOCK, Material.OAK_SAPLING, Material.WHEAT, Material.SUNFLOWER,
            Material.WATER_BUCKET, Material.NETHERRACK, Material.END_STONE, Material.OBSIDIAN,
            Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT, Material.IRON_INGOT,
            Material.SKELETON_SKULL, Material.CAKE, Material.SHIELD, Material.COMPASS);

    private HomeIcons() {
    }

    /**
     * The block to draw for a home.
     *
     * <p>Never null and never a block that cannot be drawn: a material that no longer resolves, or one
     * that is a block and not an item, falls back to the world's own icon rather than taking the whole
     * page down over one line in a file.
     */
    public static Material materialFor(Home home) {
        if (home == null) {
            return Material.RED_BED;
        }
        Material chosen = home.icon().map(HomeIcons::resolve).orElse(null);
        return chosen != null ? chosen : forWorld(home.world());
    }

    /** A material by name, or null when this server has never heard of it. */
    private static Material resolve(String name) {
        Material found = Material.matchMaterial(name);
        // isItem, not just non-null: a config or an older save can name a block that exists and can
        // never be put in an inventory slot, and drawing one throws.
        return found != null && found.isItem() ? found : null;
    }

    /**
     * What a home with no chosen block looks like: whatever its world is.
     *
     * <p>A world that is not loaded is a barrier, which is the same thing the lore says in words — the
     * icon is what somebody sees first.
     */
    public static Material forWorld(String worldName) {
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            return Material.BARRIER;
        }
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    /** {@code CRAFTING_TABLE} reads as "Crafting table". */
    public static String readable(Material material) {
        if (material == null) {
            return "Something";
        }
        String words = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
