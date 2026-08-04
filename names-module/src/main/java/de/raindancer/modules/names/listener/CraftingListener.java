package de.raindancer.modules.names.listener;

import de.raindancer.modules.names.NamesServices;
import de.raindancer.modules.names.store.StyleTags;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;

import java.util.UUID;

/**
 * The crafting grid: shows what a layout would make, and hands it over when the player takes it.
 *
 * <p>Events only. Every decision is {@code rules.CraftRule}'s and every effect is
 * {@code service.CraftService}'s, which is what keeps the part that decides whether somebody's items
 * are consumed testable without a server.
 *
 * <h2>Vanilla always wins</h2>
 * Nothing here runs when a real recipe matches the grid ({@code event.getRecipe() != null}), and the
 * click handler only fires for a result this module marked. A grid holding a name tag and a stick that
 * turns out to be some other plugin's recipe is not ours to touch, and no click on any other result slot
 * in the game changes behaviour.
 */
public final class CraftingListener implements INamesListener {

    /** The result slot of both the 2×2 inventory grid and the 3×3 table. */
    private static final int RESULT_SLOT = 0;

    private final NamesServices services;

    public CraftingListener(NamesServices services) {
        this.services = services;
    }

    // ------------------------------------------------------------------ the preview

    @EventHandler(ignoreCancelled = true)
    public void onPrepare(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null) {
            return;
        }
        services.crafting().preview(event.getInventory());
    }

    // ------------------------------------------------------------------ taking it

    /**
     * Handles the click on the result slot.
     *
     * <p>{@link InventoryClickEvent} rather than {@code CraftItemEvent}: the latter is Bukkit telling
     * you a <em>recipe</em> was crafted, and there is no recipe here. This is the only event that is
     * guaranteed to fire.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onTake(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory() instanceof CraftingInventory inventory)
                || event.getClickedInventory() != inventory
                || event.getRawSlot() != RESULT_SLOT
                || !StyleTags.isPreview(event.getCurrentItem())
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Ours, so nothing vanilla would do to it is wanted. From here the event is cancelled whatever
        // happens: a click we do not know how to serve must leave the grid untouched rather than
        // half-crafted.
        event.setCancelled(true);
        services.crafting().take(player, inventory, event.getClick(), event.getCursor());
    }

    /** Dragging over the result slot would move an item this module has not charged for. */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory() instanceof CraftingInventory
                && event.getRawSlots().contains(RESULT_SLOT)
                && StyleTags.isPreview(event.getView().getTopInventory().getItem(RESULT_SLOT))) {
            event.setCancelled(true);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing to forget: the only state is the grid, which belongs to the window and goes with it.
    }

    @Override
    public String describe() {
        return "the crafting grid — the preview, the click that takes it, and the drag that must not";
    }
}
