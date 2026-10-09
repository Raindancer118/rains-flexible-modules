package de.raindancer.modules.essentials.listener;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.rules.AdminKeepApartRule;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.block.Container;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.UUID;

/**
 * Admin items stay on the admin side: dying drops nothing, and — unless the owner switched it off — nothing
 * is dropped, picked up, stored, traded or put on display. Windows that keep items can still be looked into.
 */
public final class AdminModeListener implements IEssentialsListener {

    private final EssentialsServices services;
    private final AdminKeepApartRule rule = new AdminKeepApartRule();

    public AdminModeListener(EssentialsServices services) {
        this.services = services;
    }

    private boolean apart(Player player) {
        return services.adminMode().keepsItemsApart(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!services.adminMode().isInAdminMode(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (apart(event.getPlayer())) {
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && apart(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && apart(player) && closed(event.getView())) {
            services.messages().send(player, "essentials.admin.look-only");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && apart(player) && closed(event.getView())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && apart(player) && closed(event.getView())) {
            event.setCancelled(true);
        }
    }

    /** A window that would keep what is put in it — never one of our own menus, which handle their clicks. */
    private boolean closed(InventoryView view) {
        Inventory top = view.getTopInventory();
        return !(top.getHolder(false) instanceof Menu) && !rule.mayUseWindow(top.getType().name());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !apart(event.getPlayer())) {
            return;
        }
        if (!rule.mayUseBlock(event.getClickedBlock().getType().name())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        if ((event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof Allay)
                && apart(event.getPlayer())) {
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (apart(event.getPlayer())) {
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    /** A shulker box (or any container) placed full would leave its contents in the world. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack placed = event.getItemInHand();
        if (apart(event.getPlayer()) && placed.getItemMeta() instanceof BlockStateMeta meta && meta.hasBlockState()
                && meta.getBlockState() instanceof Container container && !container.getInventory().isEmpty()) {
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "essentials.admin.kept-apart");
        }
    }

    @Override
    public void forget(UUID player) {
        // Holds nothing per player; the service does.
    }
}
