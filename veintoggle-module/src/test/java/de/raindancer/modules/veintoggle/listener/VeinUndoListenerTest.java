package de.raindancer.modules.veintoggle.listener;

import de.miraculixx.veinminer.VeinMinerEvent;
import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.VeinRule;
import de.raindancer.modules.veintoggle.store.RestoredBlocks;
import de.raindancer.modules.veintoggle.store.VeinHistory;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a vein broke and dropped is written down as it happens, and a put-back block drops what it cost. */
class VeinUndoListenerTest {

    private static final long WINDOW = 60_000L;

    private final World world = mock(World.class);
    private final UUID worldId = UUID.randomUUID();
    private final BlockData ore = mock(BlockData.class);
    private final Player player = mock(Player.class);
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final VeinHistory history = new VeinHistory();
    private final RestoredBlocks restored = new RestoredBlocks();
    private final java.util.concurrent.atomic.AtomicInteger tick = new java.util.concurrent.atomic.AtomicInteger(100);
    private final VeinUndoListener listener = new VeinUndoListener(new VeinRule(), history, restored, clock::get,
            tick::get);

    VeinUndoListenerTest() {
        when(world.getUID()).thenReturn(worldId);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world);
    }

    private Block block(int x) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(12);
        when(block.getZ()).thenReturn(0);
        when(block.getBlockData()).thenReturn(ore);
        when(block.getLocation()).thenReturn(new Location(world, x, 12, 0));
        return block;
    }

    private ItemStack stack() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.clone()).thenReturn(stack);
        when(stack.getAmount()).thenReturn(1);
        when(stack.isSimilar(stack)).thenReturn(true);
        return stack;
    }

    private Item item(int x, ItemStack holding) {
        Item item = item(x);
        when(item.getItemStack()).thenReturn(holding);
        return item;
    }

    private Item item(int x) {
        Item item = mock(Item.class);
        when(item.getUniqueId()).thenReturn(UUID.randomUUID());
        when(item.getWorld()).thenReturn(world);
        when(item.getLocation()).thenReturn(new Location(world, x + 0.5, 12.5, 0.5));
        return item;
    }

    private BlockKey at(int x) {
        return new BlockKey(worldId, x, 12, 0);
    }

    private VeinOperation latest() {
        return history.latest(player.getUniqueId(), clock.get(), WINDOW).orElseThrow();
    }

    @Test
    @DisplayName("a whole vein is written down: the block broken by hand, every extra block, and what each dropped")
    void records() {
        Block first = block(0);
        Block second = block(1);
        ItemStack handDrop = stack();
        ItemStack veinDrop = stack();
        Item dropped = item(0);
        when(dropped.getItemStack()).thenReturn(handDrop);

        BlockBreakEvent hand = new BlockBreakEvent(first, player);
        listener.onBreakEarly(hand);
        listener.onBreak(hand);
        listener.onDrop(new BlockDropItemEvent(first, mock(BlockState.class), player, new ArrayList<>(List.of(dropped))));
        BlockBreakEvent vein = new VeinMinerEvent.VeinminerEvent(second, player, first.getLocation(), 3);
        listener.onBreakEarly(vein);
        listener.onBreak(vein);
        listener.onExp(new VeinMinerEvent.VeinminerDropEvent(second, mock(BlockState.class), player,
                new ArrayList<>(List.of(veinDrop)), 3));

        VeinOperation operation = latest();
        assertThat(operation.source()).isEqualTo(at(0));
        assertThat(operation.blocks()).extracting(BrokenBlock::at).containsExactly(at(0), at(1));
        assertThat(operation.find(at(0)).orElseThrow().drops()).containsExactly(handDrop);
        assertThat(operation.find(at(1)).orElseThrow().drops()).containsExactly(veinDrop);
        assertThat(operation.find(at(1)).orElseThrow().data()).isSameAs(ore);
        assertThat(operation.find(at(0)).orElseThrow().dropEntities()).containsExactly(dropped.getUniqueId());
        assertThat(vein.getExpToDrop()).as("an ordinary vein block keeps its experience").isEqualTo(3);
    }

    @Test
    @DisplayName("a refused vein block is not written down")
    void cancelledIsNotRecorded() {
        BlockBreakEvent vein = new VeinMinerEvent.VeinminerEvent(block(1), player, block(0).getLocation(), 0);
        vein.setCancelled(true);
        // ignoreCancelled keeps Bukkit from calling it; the handler must not count on that alone either.
        listener.onBreak(vein);
        assertThat(history.latest(player.getUniqueId(), clock.get(), WINDOW)).isEmpty();
    }

    @Test
    @DisplayName("a put-back block drops exactly what was taken for it and no experience — no rerolling fortune, no XP farm")
    void restoredDropsWhatItCost() {
        Block again = block(1);
        ItemStack diamond = stack();
        restored.mark(at(1), ore, List.of(diamond));

        BlockBreakEvent vein = new VeinMinerEvent.VeinminerEvent(again, player, block(0).getLocation(), 7);
        listener.onBreakEarly(vein);
        assertThat(vein.isDropItems()).isFalse();
        assertThat(vein.getExpToDrop()).isZero();
        Item redropped = item(1);
        when(world.dropItemNaturally(any(Location.class), org.mockito.ArgumentMatchers.eq(diamond))).thenReturn(redropped);
        listener.onBreak(vein);

        verify(world).dropItemNaturally(any(Location.class), org.mockito.ArgumentMatchers.eq(diamond));
        assertThat(restored.holds(at(1), ore)).isFalse();
        assertThat(latest().find(at(1)).orElseThrow().drops()).as("so it can be undone again").containsExactly(diamond);
        assertThat(latest().find(at(1)).orElseThrow().dropEntities()).containsExactly(redropped.getUniqueId());
    }

    @Test
    @DisplayName("broken in creative, a put-back block drops nothing and forgets its mark")
    void creative() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        restored.mark(at(1), ore, List.of(stack()));

        BlockBreakEvent hand = new BlockBreakEvent(block(1), player);
        listener.onBreakEarly(hand);
        listener.onBreak(hand);

        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
        assertThat(restored.holds(at(1), ore)).isFalse();
    }

    @Test
    @DisplayName("somebody who left is forgotten")
    void forget() {
        listener.onBreak(new VeinMinerEvent.VeinminerEvent(block(1), player, block(0).getLocation(), 0));
        listener.forget(player.getUniqueId());
        assertThat(history.latest(player.getUniqueId(), clock.get(), WINDOW)).isEmpty();
    }

    @Test
    @DisplayName("the items Veinminer spawns straight after its drop event are known as this vein's, and nothing later is")
    void veinDropEntities() {
        Block first = block(0);
        Block second = block(1);
        ItemStack diamond = stack();
        listener.onBreak(new VeinMinerEvent.VeinminerEvent(second, player, first.getLocation(), 0));
        listener.onExp(new VeinMinerEvent.VeinminerDropEvent(second, mock(BlockState.class), player,
                new ArrayList<>(List.of(diamond)), 0));
        Item ours = item(1, diamond);
        listener.onItemSpawn(new org.bukkit.event.entity.ItemSpawnEvent(ours));
        Item extra = item(1, diamond);
        listener.onItemSpawn(new org.bukkit.event.entity.ItemSpawnEvent(extra));
        tick.incrementAndGet();
        ItemStack two = stack();
        listener.onExp(new VeinMinerEvent.VeinminerDropEvent(block(2), mock(BlockState.class), player,
                new ArrayList<>(List.of(two)), 0));
        tick.incrementAndGet();
        Item later = item(2, two);
        listener.onItemSpawn(new org.bukkit.event.entity.ItemSpawnEvent(later));

        assertThat(latest().find(at(1)).orElseThrow().dropEntities())
                .as("one item for one stack; the next spawn is somebody else's").containsExactly(ours.getUniqueId());
    }

    @Test
    @DisplayName("an item spawned far from the vein block is not taken for one of its drops")
    void farItemIsNotTheVeins() {
        Block second = block(1);
        listener.onBreak(new VeinMinerEvent.VeinminerEvent(second, player, block(0).getLocation(), 0));
        ItemStack diamond = stack();
        listener.onExp(new VeinMinerEvent.VeinminerDropEvent(second, mock(BlockState.class), player,
                new ArrayList<>(List.of(diamond)), 0));
        listener.onItemSpawn(new org.bukkit.event.entity.ItemSpawnEvent(item(40, diamond)));

        assertThat(latest().find(at(1)).orElseThrow().dropEntities()).isEmpty();
    }

    @Test
    @DisplayName("a different item, or one after the next block break, is never counted as the vein's — even right there")
    void onlyTheAnnouncedStacks() {
        Block second = block(1);
        listener.onBreak(new VeinMinerEvent.VeinminerEvent(second, player, block(0).getLocation(), 0));
        ItemStack diamond = stack();
        listener.onExp(new VeinMinerEvent.VeinminerDropEvent(second, mock(BlockState.class), player,
                new ArrayList<>(List.of(diamond)), 0));
        listener.onItemSpawn(new org.bukkit.event.entity.ItemSpawnEvent(item(1, stack())));

        BlockBreakEvent somebodyElses = new BlockBreakEvent(block(5), player);
        listener.onBreakEarly(somebodyElses);
        listener.onItemSpawn(new org.bukkit.event.entity.ItemSpawnEvent(item(1, diamond)));

        assertThat(latest().find(at(1)).orElseThrow().dropEntities()).isEmpty();
    }
}
