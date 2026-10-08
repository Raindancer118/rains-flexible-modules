package de.raindancer.modules.economy.listener;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.store.CashTags;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/**
 * Right clicking cash pays it in. Cash is never placed — a coin made of a block would become an ordinary
 * block and its value would vanish with the tag — and never passes through the creative inventory.
 */
public final class CashListener implements IEconomyListener {

    private final EconomyServices services;

    public CashListener(EconomyServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!CashTags.isCash(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!services.config().depositOnRightClick() || !player.hasPermission(PermissionNodes.CASH)) {
            return;
        }
        if (player.isSneaking()) {
            services.cash().depositAll(player);
        } else {
            services.cash().depositHand(player);
        }
    }

    /**
     * The creative inventory lets a client conjure any item it likes, a perfect copy of a coin included —
     * the seal cannot tell a clone from the original. So cash never passes through it.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreative(org.bukkit.event.inventory.InventoryCreativeEvent event) {
        if (CashTags.isCash(event.getCursor()) || CashTags.isCash(event.getCurrentItem())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                services.messages().send(player, "economy.cash.no-creative");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (CashTags.isCash(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered: what cash is worth travels on the item.
    }

    @Override
    public String describe() {
        return "paying cash in with a right click; keeping cash from being placed or cloned in creative";
    }
}
