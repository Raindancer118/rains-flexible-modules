package de.raindancer.modules.manhunt.tracker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One kind of this module's compasses, as items: tagged with a key of its own, found, taken back and
 * handed over the same way for all three.
 *
 * <p>The tag is read through {@link ItemStack#getPersistentDataContainer()}, a view that does not copy
 * the item's meta — the sweep looks through every holder's inventory several times a second.
 */
final class CompassItems {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final NamespacedKey key;
    private final Set<Material> materials;

    CompassItems(Plugin plugin, String keyName, Material first, Material... more) {
        this.key = new NamespacedKey(plugin, keyName);
        this.materials = EnumSet.of(first, more);
    }

    void tag(ItemMeta meta, String value) {
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }

    /** The tag on {@code stack}, or null for anything that is not one of these. */
    String tagOf(ItemStack stack) {
        if (stack == null || !materials.contains(stack.getType())) {
            return null;
        }
        return stack.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    boolean is(ItemStack stack, Predicate<String> tag) {
        String found = tagOf(stack);
        return found != null && tag.test(found);
    }

    /** The first slot holding one, or -1. */
    int slotOf(Player player, Predicate<String> tag) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (is(contents[slot], tag)) {
                return slot;
            }
        }
        return -1;
    }

    void removeAll(Player player, Predicate<String> tag) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (is(contents[slot], tag)) {
                inventory.setItem(slot, null);
            }
        }
    }

    boolean inHand(Player player, Predicate<String> tag) {
        PlayerInventory inventory = player.getInventory();
        return is(inventory.getItemInMainHand(), tag) || is(inventory.getItemInOffHand(), tag);
    }

    /**
     * Into the inventory, or at their feet when it does not fit — {@code addItem} hands back what it
     * could not place rather than throwing, and throwing that away loses the compass.
     */
    static void handTo(Player player, ItemStack stack) {
        for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
        }
    }

    /** One line of an item's name or lore — never the italic vanilla gives custom text. */
    static Component line(String mini) {
        return MINI.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    /** A player-supplied name never reaches MiniMessage as markup. */
    static String safe(String raw) {
        return raw == null ? "somebody" : raw.replace("<", "").replace(">", "");
    }
}
