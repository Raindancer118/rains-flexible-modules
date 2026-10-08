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
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Writes down what each vein broke and dropped, so {@code /vein undo} can put it back; and makes a
 * block an undo put back drop exactly what was taken for it.
 *
 * <p>Recording is at MONITOR: only a break that really happens is written down, and the drops are the
 * ones left after every other plugin had its say.
 *
 * <p>The item entities are written down too, so an undo takes back from the ground only what this vein
 * dropped. A hand break names its entities in BlockDropItemEvent. Veinminer does not: it calls
 * {@code world.dropItem} once per stack right after its drop event, in the same call on the same
 * thread — so the item spawns that follow it, in the same tick and right at that block, are its.
 */
public final class VeinUndoListener implements Listener {

    /** How far from the block, or the vein's source when Veinminer merges drops, its items appear. */
    private static final double DROP_REACH = 2.0;

    /** Veinminer's drops still expected on this thread: where, in which tick, and how many stacks. */
    private static final class Expected {
        final UUID player;
        final BlockKey at;
        final BlockKey source;
        final int tick;
        int left;

        Expected(UUID player, BlockKey at, BlockKey source, int tick, int left) {
            this.player = player;
            this.at = at;
            this.source = source;
            this.tick = tick;
            this.left = left;
        }
    }

    private final ThreadLocal<Expected> expected = new ThreadLocal<>();
    private final VeinRule rule;
    private final VeinHistory history;
    private final RestoredBlocks restored;
    private final LongSupplier clock;
    private final IntSupplier tick;

    public VeinUndoListener(VeinRule rule, VeinHistory history, RestoredBlocks restored, LongSupplier clock,
                            IntSupplier tick) {
        this.rule = rule;
        this.history = history;
        this.restored = restored;
        this.clock = clock;
        this.tick = tick;
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

        UUID id = player.getUniqueId();
        boolean vein = rule.isVeinBlock(event);
        if (vein) {
            history.veinBlock(id, BlockKey.of(VeinminerEvents.sourceOf(event)), new BrokenBlock(at, data),
                    clock.getAsLong());
        } else if (!creative) {
            history.brokeByHand(id, new BrokenBlock(at, data), clock.getAsLong());
        }
        if (owed.isEmpty() || creative) {
            return;
        }
        List<UUID> entities = new ArrayList<>();
        for (ItemStack stack : owed.get()) {
            Item dropped = block.getWorld().dropItemNaturally(at.centre(block.getWorld()), stack.clone());
            if (dropped != null) {
                entities.add(dropped.getUniqueId());
            }
        }
        if (vein) {
            history.veinDrops(id, at, owed.get());
            entities.forEach(entity -> history.veinDropEntity(id, at, entity));
        } else {
            history.handDrops(id, at, owed.get(), entities);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        List<ItemStack> drops = new ArrayList<>();
        List<UUID> entities = new ArrayList<>();
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            if (stack != null && !stack.isEmpty()) {
                drops.add(stack.clone());
                entities.add(item.getUniqueId());
            }
        }
        history.handDrops(event.getPlayer().getUniqueId(), BlockKey.of(event.getBlock()), drops, entities);
    }

    /** Veinminer's drop event is a BlockExpEvent; nothing else that arrives here is wanted. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExp(BlockExpEvent event) {
        if (!rule.isVeinDrop(event)) {
            return;
        }
        expected.remove();
        Player player = VeinminerEvents.playerOf(event);
        if (player == null) {
            return;
        }
        BlockKey at = BlockKey.of(event.getBlock());
        List<ItemStack> items = VeinminerEvents.itemsOf(event);
        history.veinDrops(player.getUniqueId(), at, items);
        if (!items.isEmpty()) {
            BlockKey source = history.sourceOf(player.getUniqueId(), at).orElse(at);
            expected.set(new Expected(player.getUniqueId(), at, source, tick.getAsInt(), items.size()));
        }
    }

    /** Veinminer's items, spawning right after its drop event; see the class comment. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Expected waiting = expected.get();
        if (waiting == null) {
            return;
        }
        if (waiting.tick != tick.getAsInt() || waiting.left <= 0) {
            expected.remove();
            return;
        }
        Item item = event.getEntity();
        BlockKey spawned = BlockKey.of(item.getLocation());
        if (spawned == null || !spawned.world().equals(waiting.at.world())
                || (spawned.distance(waiting.at) > DROP_REACH && spawned.distance(waiting.source) > DROP_REACH)) {
            return;
        }
        history.veinDropEntity(waiting.player, waiting.at, item.getUniqueId());
        if (--waiting.left == 0) {
            expected.remove();
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
