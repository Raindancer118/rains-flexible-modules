package de.raindancer.modules.anticheat.visual;

import de.raindancer.modules.anticheat.model.ReplayLoadout;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** A player's armour, off hand and hotbar as a {@link ReplayLoadout}, and back into items. */
public final class Loadouts {

    private Loadouts() {
    }

    /** What they wear and carry now; null if it cannot be read. */
    public static ReplayLoadout of(Player player) {
        try {
            PlayerInventory inventory = player.getInventory();
            List<String> armour = new ArrayList<>();
            for (ItemStack piece : new ItemStack[]{inventory.getHelmet(), inventory.getChestplate(),
                    inventory.getLeggings(), inventory.getBoots()}) {
                armour.add(encode(piece));
            }
            List<String> hotbar = new ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                hotbar.add(encode(inventory.getItem(slot)));
            }
            return new ReplayLoadout(armour, encode(inventory.getItemInOffHand()), hotbar);
        } catch (RuntimeException unreadable) {
            // A replay without clothes is still a replay.
            return null;
        }
    }

    public static ItemStack item(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (RuntimeException unreadable) {
            // An item from a newer or older game version than this one: left out rather than guessed.
            return null;
        }
    }

    private static String encode(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return null;
        }
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }
}
