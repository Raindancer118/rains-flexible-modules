package de.raindancer.modules.claims;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;

/**
 * Charges and refunds the two currencies the plugin supports: items and experience.
 * <p>
 * Item matching uses {@link ItemStack#isSimilar(ItemStack)}, so a cost defined as "an enchanted book with
 * Mending" really does require that exact book and not just any enchanted book.
 */
public final class CostService {

    /** Result of a charge attempt. */
    public record Charge(boolean success, String shortfallDescription) {
        public static Charge ok() {
            return new Charge(true, "");
        }

        public static Charge failed(String description) {
            return new Charge(false, description);
        }
    }

    /** Experience points needed to reach a level, mirroring vanilla's curve. */
    public static int totalExperienceForLevel(int level) {
        if (level <= 0) {
            return 0;
        }
        if (level <= 16) {
            return level * level + 6 * level;
        }
        if (level <= 31) {
            return (int) (2.5D * level * level - 40.5D * level + 360.0D);
        }
        return (int) (4.5D * level * level - 162.5D * level + 2220.0D);
    }

    /** A player's total experience points, which Bukkit does not expose directly. */
    public static int totalExperience(Player player) {
        int level = player.getLevel();
        return totalExperienceForLevel(level)
                + Math.round(player.getExp() * player.getExpToLevel());
    }

    private static void setTotalExperience(Player player, int total) {
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        player.giveExp(Math.max(0, total));
    }

    public boolean canAfford(Player player, CostType type, int amount, ItemStack item) {
        return switch (type) {
            case NONE -> true;
            case ITEM -> item != null && countMatching(player.getInventory(), item) >= amount;
            case XP_LEVELS -> player.getLevel() >= amount;
            case XP_POINTS -> totalExperience(player) >= amount;
        };
    }

    public Charge charge(Player player, CostType type, int amount, ItemStack item) {
        switch (type) {
            case NONE -> {
                return Charge.ok();
            }
            case ITEM -> {
                if (item == null) {
                    return Charge.failed("no item configured");
                }
                int have = countMatching(player.getInventory(), item);
                if (have < amount) {
                    return Charge.failed((amount - have) + "x more of the required item");
                }
                removeMatching(player.getInventory(), item, amount);
                return Charge.ok();
            }
            case XP_LEVELS -> {
                if (player.getLevel() < amount) {
                    return Charge.failed((amount - player.getLevel()) + " more experience level(s)");
                }
                player.setLevel(player.getLevel() - amount);
                return Charge.ok();
            }
            case XP_POINTS -> {
                int total = totalExperience(player);
                if (total < amount) {
                    return Charge.failed((amount - total) + " more experience point(s)");
                }
                setTotalExperience(player, total - amount);
                return Charge.ok();
            }
            default -> {
                return Charge.failed("unsupported cost type");
            }
        }
    }

    /** Gives the cost back, dropping items at the player's feet when the inventory is full. */
    public void refund(Player player, CostType type, int amount, ItemStack item) {
        switch (type) {
            case NONE -> {
            }
            case ITEM -> {
                if (item == null) {
                    return;
                }
                int remaining = amount;
                while (remaining > 0) {
                    ItemStack stack = item.clone();
                    int size = Math.min(remaining, stack.getMaxStackSize());
                    stack.setAmount(size);
                    remaining -= size;
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
                    for (ItemStack leftover : leftovers.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
            }
            case XP_LEVELS -> player.giveExpLevels(amount);
            case XP_POINTS -> player.giveExp(amount);
        }
    }

    /** Puts items into the inventory, dropping the remainder. Returns what had to be dropped. */
    public int giveOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftovers = new HashMap<>(player.getInventory().addItem(stack.clone()));
        int dropped = 0;
        for (ItemStack leftover : leftovers.values()) {
            dropped += leftover.getAmount();
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        return dropped;
    }

    public int countMatching(PlayerInventory inventory, ItemStack template) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.isSimilar(template)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private void removeMatching(PlayerInventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.isSimilar(template)) {
                continue;
            }
            int taken = Math.min(remaining, stack.getAmount());
            remaining -= taken;
            if (stack.getAmount() - taken <= 0) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - taken);
            }
        }
        inventory.setStorageContents(contents);
    }

    /** Human readable cost label for GUIs and messages. */
    public Component describe(CostType type, int amount, ItemStack item) {
        return switch (type) {
            case NONE -> Component.text("free");
            case ITEM -> Component.text(amount + "x ")
                    .append(item == null
                            ? Component.text("<unset item>")
                            : (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()
                                    ? item.getItemMeta().displayName()
                                    : Component.translatable(item)));
            case XP_LEVELS -> Component.text(amount + " level" + (amount == 1 ? "" : "s"));
            case XP_POINTS -> Component.text(amount + " XP");
        };
    }
}
