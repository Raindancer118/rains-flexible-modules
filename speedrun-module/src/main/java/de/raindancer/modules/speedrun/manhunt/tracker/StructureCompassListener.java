package de.raindancer.modules.speedrun.manhunt.tracker;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * The structure compass' clicks and its drop — a death is {@link CompassKeeper}'s. Registered for the life of the module — it
 * only ever acts on its own items, which exist only during a hunt.
 */
public final class StructureCompassListener implements Listener {

    private final StructureCompassService structures;

    public StructureCompassListener(StructureCompassService structures) {
        this.structures = structures;
    }

    /** A right-click on the blank one opens the list; on a chosen one it does nothing, lodestones included. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!structures.isStructureCompass(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (structures.isBlank(event.getItem())) {
            structures.openChooser(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        structures.onDrop(event);
    }
}
