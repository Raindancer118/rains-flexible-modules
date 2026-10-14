package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.service.ChestLinkService;
import de.raindancer.modules.enchants.service.ToolService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/**
 * The tool enchantments on the server's events. Drops pass through in a fixed order, by priority:
 * Harvester keeps its seed (LOWEST), Smelting turns them (LOW), the economy's Auto Sell sells them
 * (NORMAL), Chest Link sends the rest away (HIGH), Telekinesis picks up what is left (HIGHEST).
 */
public final class ToolListener implements IEnchantsListener {

    private final ToolService tools;
    private final ChestLinkService links;

    public ToolListener(ToolService tools, ChestLinkService links) {
        this.tools = tools;
        this.links = links;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (tools.beforeBreak(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        event.setExpToDrop(tools.experience(event.getPlayer(), event.getExpToDrop()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent event) {
        tools.afterBreak(event.getPlayer(), event.getBlock(), event.getBlock().getType(),
                event.getBlock().getType().getHardness());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void seed(BlockDropItemEvent event) {
        tools.keepSeed(event.getPlayer(), event.getBlock(), event.getItems());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void smelt(BlockDropItemEvent event) {
        tools.smelt(event.getPlayer(), event.getBlock(), event.getItems());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void link(BlockDropItemEvent event) {
        links.collect(event.getPlayer(), event.getBlock().getLocation(), event.getItems());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickUp(BlockDropItemEvent event) {
        tools.pickUp(event.getPlayer(), event.getItems());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || event instanceof PlayerDeathEvent) {
            return;
        }
        event.setDroppedExp(tools.mobDied(killer, event.getDrops(), event.getDroppedExp()));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent event) {
        if (!event.getKeepInventory()) {
            tools.keepSoulbound(event.getPlayer(), event.getDrops(), event.getItemsToKeep());
        }
    }

    /** Ignores cancelled clicks on purpose: a claim cancels the click on a chest it protects, and that is the answer wanted. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }
        boolean allowed = event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY;
        if (links.link(event.getPlayer(), event.getClickedBlock(), allowed)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        tools.forget(player);
        links.forget(player);
    }
}
