package de.raindancer.modules.names.listener;

import de.raindancer.modules.names.NamesServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Right-clicking a water cauldron with a styled name tag.
 *
 * <p>Events only; the washing itself is {@code service.WashService}, which also owns the reason a
 * cauldron is the place this happens at all.
 *
 * <p>{@link EventPriority#HIGH} and {@code ignoreCancelled}: a plugin that has already refused the
 * interaction — a claim, most likely — has refused it, and a tag must not be washed inside somebody
 * else's build because this module looked at the event first.
 */
public final class CauldronListener implements INamesListener {

    private final NamesServices services;

    public CauldronListener(NamesServices services) {
        this.services = services;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onWash(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) {
            return;
        }
        ItemStack held = event.getItem();
        if (!services.washing().washes(event.getClickedBlock(), held)) {
            return;
        }
        // Cancelled before anything is changed: an ordinary name tag goes on filling and emptying
        // cauldrons exactly as it always has, and only a tag with a style on it is taken over.
        event.setCancelled(true);
        services.washing().wash(event.getPlayer(), event.getHand(), event.getClickedBlock(), held);
    }

    @Override
    public void forget(UUID player) {
        // Nothing to forget: a wash begins and ends inside one interaction.
    }

    @Override
    public String describe() {
        return "washing a styled name tag clean in a water cauldron";
    }
}
