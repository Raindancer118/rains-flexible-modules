package de.raindancer.modules.names.listener;

import de.raindancer.modules.names.NamesServices;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.store.StyleTags;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Naming a mob with a styled tag.
 *
 * <p>{@link EventPriority#MONITOR} and {@code ignoreCancelled}: this only watches. Whether the mob may
 * be named at all is somebody else's decision — a claim's, usually — and the painting happens a tick
 * later and only if the name was actually applied. See {@code service.MobNameService}.
 */
public final class MobNameListener implements INamesListener {

    private final NamesServices services;

    public MobNameListener(NamesServices services) {
        this.services = services;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onName(PlayerInteractEntityEvent event) {
        if (!services.mobNames().enabled() || event.getHand() == null) {
            return;
        }
        ItemStack tag = event.getPlayer().getInventory().getItem(event.getHand());
        if (tag == null || tag.getType() != Material.NAME_TAG || !tag.hasItemMeta()
                || !tag.getItemMeta().hasDisplayName()) {
            return;
        }
        NameStyle style = StyleTags.read(tag);
        if (style.isEmpty() || !(event.getRightClicked() instanceof LivingEntity target)) {
            return;
        }
        services.mobNames().paint(target, tag.getItemMeta().displayName(), style);
    }

    @Override
    public void forget(UUID player) {
        // Nothing to forget: what is remembered for a tick is remembered by the scheduler, against the
        // mob rather than the player, and it checks the mob is still there before it does anything.
    }

    @Override
    public String describe() {
        return "painting a mob's name when it is named with a styled tag";
    }
}
