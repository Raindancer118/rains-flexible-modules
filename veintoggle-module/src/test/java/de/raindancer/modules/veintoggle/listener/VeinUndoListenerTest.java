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
    private final VeinUndoListener listener = new VeinUndoListener(new VeinRule(), history, restored, clock::get);

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
        return stack;
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
        Item dropped = mock(Item.class);
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
        listener.onBreak(vein);

        verify(world).dropItemNaturally(any(Location.class), org.mockito.ArgumentMatchers.eq(diamond));
        assertThat(restored.holds(at(1), ore)).isFalse();
        assertThat(latest().find(at(1)).orElseThrow().drops()).as("so it can be undone again").containsExactly(diamond);
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
}
