package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.screen.VaultMenu;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.UUID;

/**
 * Stops a drag from laying real items onto the vault's page.
 *
 * <p>Core's menu listener lets drags through on any screen that reads the player's own inventory, so the
 * bottom half keeps working. On the vault the top half is copies, which a drag would overwrite with real
 * items that vanish at the next redraw. Putting something in is a click or a shift-click instead.
 */
public final class VaultListener implements IModerationListener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof VaultMenu)) {
            return;
        }
        int top = event.getInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < top) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody.
    }
}
