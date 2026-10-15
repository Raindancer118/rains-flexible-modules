package de.raindancer.modules.oneblock.listener;

import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.service.OneBlockService;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one block's events. Breaking it by hand counts — whatever Core's protection let through; nothing else may take
 * it: no explosion, piston, fire, decay, melting or fall. An end island never gets its dragon.
 */
public final class OneBlockListener implements IOneBlockListener {

    private final OneBlockService oneBlock;

    public OneBlockListener(OneBlockService oneBlock) {
        this.oneBlock = oneBlock;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        oneBlock.targetOf(event.getBlock()).ifPresent(target ->
                oneBlock.broken(target, event.getPlayer(), event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        oneBlock.targetOf(event.getBlock()).ifPresent(target -> {
            for (Item item : event.getItems()) {
                List<ItemStack> one = new ArrayList<>(List.of(item.getItemStack()));
                oneBlock.smelt(target, one);
                if (!one.isEmpty() && !one.getFirst().equals(item.getItemStack())) {
                    item.setItemStack(one.getFirst());
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(oneBlock::isOneBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(oneBlock::isOneBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(oneBlock::isOneBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(oneBlock::isOneBlock)) {
            event.setCancelled(true);
        }
    }

    /** Falling sand, an enderman, a ravager: anything but a player's break leaves the block where it is. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent event) {
        if (oneBlock.isOneBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        cancelIfOneBlock(event.getBlock(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        cancelIfOneBlock(event.getBlock(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        cancelIfOneBlock(event.getBlock(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() == EntityType.ENDER_DRAGON
                && Island.isIslandWorld(event.getLocation().getWorld().getName())) {
            event.setCancelled(true);
        }
    }

    private void cancelIfOneBlock(Block block, Runnable cancel) {
        if (oneBlock.isOneBlock(block)) {
            cancel.run();
        }
    }

    /** Nothing kept per player. */
    @Override
    public void forget(UUID player) {
    }
}
