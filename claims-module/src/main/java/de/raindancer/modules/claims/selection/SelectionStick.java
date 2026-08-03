package de.raindancer.modules.claims.selection;

import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.util.Items;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * The Claimborder Selection Stick.
 * <p>
 * Identified by a persistent data tag rather than its display name, so renaming it in an anvil cannot
 * turn an ordinary stick into a claiming tool or vice versa. It is handed out on demand and taken away
 * the moment a selection is finished or cancelled.
 */
public final class SelectionStick {

    private final Plugin plugin;
    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final Messages messages;
    private final NamespacedKey key;

    public SelectionStick(Plugin plugin, ClaimSettings settings, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.key = new NamespacedKey(plugin, "selection_stick");
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>Called on reload. The field is a snapshot rather than a live view, so nothing here has to think about a
     * value changing halfway through a calculation — and replacing the whole snapshot means a reload takes effect
     * on the next event rather than on the next restart.
     */
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    public NamespacedKey key() {
        return key;
    }

    public boolean isStick(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** Which purpose the stick in hand was issued for. */
    public Selection.Purpose purposeOf(ItemStack stack) {
        String raw = stack.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null) {
            return Selection.Purpose.NEW_CLAIM;
        }
        try {
            return Selection.Purpose.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return Selection.Purpose.NEW_CLAIM;
        }
    }

    public ItemStack create(Selection.Purpose purpose, Selection.Mode mode) {
        String modeLabel = mode == Selection.Mode.RECTANGLE ? "Rectangle" : "Polygon";
        // A no-claim zone is the opposite of a claim, and an admin marking one out while holding the same
        // item they use for claims has no way to tell from their hotbar which they are doing. A blaze rod is
        // deliberately not the configured claim material: it reads as "administrative" at a glance, and the
        // mistake this prevents — marking a no-claim zone over land somebody wanted to claim — is one that
        // takes a while to notice.
        boolean forbidding = purpose == Selection.Purpose.NO_CLAIM_ZONE;
        return Items.of(forbidding ? org.bukkit.Material.BLAZE_ROD : settings.selectionStickMaterial())
                .name(forbidding
                        ? "<gradient:#ef4444:#f97316><bold>No-Claim Zone Stick</bold></gradient>"
                        : "<gradient:#ffd54f:#ff8f00><bold>Claimborder Selection Stick</bold></gradient>")
                .lore("<white>Mode: <yellow>" + modeLabel,
                        "<white>For: <yellow>" + label(purpose))
                .blank()
                .lore("<yellow>Right-click a block <gray>add a corner",
                        "<yellow>Left-click a block <gray>undo the last corner",
                        "<yellow>Right-click the air <gray>open the selection menu",
                        "<yellow>Shift + right-click <gray>finish now",
                        "<yellow>Shift + left-click air <gray>cancel")
                .blank()
                .lore("<dark_gray>Vanishes once the selection is done.")
                .glint(settings.selectionStickGlint())
                .hideAttributes()
                .tag(key, purpose.name())
                .build();
    }

    /** Hands the stick over, dropping it at the player's feet when the inventory is full. */
    public void give(Player player, Selection.Purpose purpose, Selection.Mode mode) {
        ItemStack stick = create(purpose, mode);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stick);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            messages.send(player, "selection.stick-dropped");
        }
    }

    /** Removes every selection stick from the player's inventory. Returns how many were taken. */
    public int revoke(Player player) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (isStick(stack)) {
                removed += stack.getAmount();
                contents[slot] = null;
            }
        }
        if (removed > 0) {
            player.getInventory().setContents(contents);
            player.updateInventory();
        }
        return removed;
    }

    public boolean holdsStick(Player player) {
        return isStick(player.getInventory().getItemInMainHand())
                || isStick(player.getInventory().getItemInOffHand());
    }

    private static String label(Selection.Purpose purpose) {
        return switch (purpose) {
            case NEW_CLAIM -> "a new claim";
            case RESIZE_CLAIM -> "resizing a claim";
            case NO_CLAIM_ZONE -> "a no-claim zone";
            case ADMIN_RESHAPE -> "an admin reshape";
        };
    }

    public Plugin plugin() {
        return plugin;
    }
}
