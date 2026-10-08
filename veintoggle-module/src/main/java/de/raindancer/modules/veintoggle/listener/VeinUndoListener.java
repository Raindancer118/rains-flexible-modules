package de.raindancer.modules.veintoggle.listener;

import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.rules.VeinRule;
import de.raindancer.modules.veintoggle.store.RestoredBlocks;
import de.raindancer.modules.veintoggle.store.VeinHistory;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Writes down what each vein broke and dropped, so {@code /vein undo} can put it back; and makes a
 * block an undo put back drop exactly what was taken for it.
 *
 * <p>Recording is at MONITOR: only a break that really happens is written down, and the drops are the
 * ones left after every other plugin had its say.
 */
public final class VeinUndoListener implements Listener {

    private final VeinRule rule;
    private final VeinHistory history;
    private final RestoredBlocks restored;
    private final LongSupplier clock;

    public VeinUndoListener(VeinRule rule, VeinHistory history, RestoredBlocks restored, LongSupplier clock) {
        this.rule = rule;
        this.history = history;
        this.restored = restored;
        this.clock = clock;
    }

    /** A put-back block drops nothing of its own; {@link #onBreak} drops what it cost instead. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakEarly(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (restored.holds(BlockKey.of(block), block.getBlockData())) {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        BlockKey at = BlockKey.of(block);
        BlockData data = block.getBlockData();
        boolean creative = player.getGameMode() == GameMode.CREATIVE;

        Optional<List<ItemStack>> owed = restored.take(at, data);
        if (owed.isPresent() && !creative) {
            owed.get().forEach(stack -> block.getWorld().dropItemNaturally(at.centre(block.getWorld()), stack.clone()));
        }

        UUID id = player.getUniqueId();
        if (rule.isVeinBlock(event)) {
            history.veinBlock(id, BlockKey.of(VeinminerEvents.sourceOf(event)), new BrokenBlock(at, data),
                    clock.getAsLong());
            owed.ifPresent(drops -> history.veinDrops(id, at, drops));
        } else if (!creative) {
            history.brokeByHand(id, new BrokenBlock(at, data), clock.getAsLong());
            owed.ifPresent(drops -> history.handDrops(id, at, drops));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        List<ItemStack> drops = event.getItems().stream()
                .map(Item::getItemStack)
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::clone)
                .toList();
        history.handDrops(event.getPlayer().getUniqueId(), BlockKey.of(event.getBlock()), drops);
    }

    /** Veinminer's drop event is a BlockExpEvent; nothing else that arrives here is wanted. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExp(BlockExpEvent event) {
        if (!rule.isVeinDrop(event)) {
            return;
        }
        Player player = VeinminerEvents.playerOf(event);
        if (player != null) {
            history.veinDrops(player.getUniqueId(), BlockKey.of(event.getBlock()), VeinminerEvents.itemsOf(event));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** An undo is for somebody who is here; leaving lets go of their veins. */
    public void forget(UUID player) {
        history.forget(player);
    }
}
