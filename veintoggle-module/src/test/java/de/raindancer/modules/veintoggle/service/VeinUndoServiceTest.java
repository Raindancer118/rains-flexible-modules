package de.raindancer.modules.veintoggle.service;

import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import de.raindancer.modules.veintoggle.store.RestoredBlocks;
import de.raindancer.modules.veintoggle.store.VeinHistory;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Undoing a vein: blocks go back only as far as what they dropped comes back too — otherwise undo is
 * a duplication machine for diamonds.
 */
class VeinUndoServiceTest {

    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final UUID worldId = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final BlockData ore = mock(BlockData.class);
    private final Map<ItemStack, String> kinds = new IdentityHashMap<>();
    private final Map<BlockKey, Block> blocks = new HashMap<>();
    private final List<Item> ground = new ArrayList<>();
    private final VeinHistory history = new VeinHistory();
    private final RestoredBlocks restored = new RestoredBlocks();
    private final java.util.Set<BlockKey> protectedSpots = new java.util.HashSet<>();
    private final VeinUndoService service = new VeinUndoService(server, new UndoRule(), history, restored,
            (who, where) -> !protectedSpots.contains(new BlockKey(worldId, where.getBlockX(), where.getBlockY(), where.getBlockZ())));
    private ItemStack[] storage = new ItemStack[36];

    VeinUndoServiceTest() {
        when(world.getUID()).thenReturn(worldId);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(true);
        when(server.isOwnedByCurrentRegion(any(org.bukkit.entity.Entity.class))).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> blocks.computeIfAbsent(
                new BlockKey(worldId, call.getArgument(0), call.getArgument(1), call.getArgument(2)), this::airAt));
        when(world.getNearbyEntitiesByType(eq(Item.class), any(Location.class), anyDouble())).thenAnswer(call -> ground);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenAnswer(call -> storage.clone());
        doAnswer(call -> {
            storage = call.getArgument(0);
            return null;
        }).when(inventory).setStorageContents(any());
    }

    private Block airAt(BlockKey key) {
        Block block = mock(Block.class);
        when(block.isReplaceable()).thenReturn(true);
        when(block.getLocation()).thenReturn(new Location(world, key.x(), key.y(), key.z()));
        return block;
    }

    /** An item stack that keeps its amount, and is similar to any other of its kind. */
    private ItemStack stack(String kind, int amount) {
        ItemStack stack = mock(ItemStack.class);
        AtomicInteger count = new AtomicInteger(amount);
        kinds.put(stack, kind);
        when(stack.getAmount()).thenAnswer(call -> count.get());
        doAnswer(call -> {
            count.set(call.getArgument(0));
            return null;
        }).when(stack).setAmount(anyInt());
        when(stack.isSimilar(any())).thenAnswer(call -> kind.equals(kinds.get(call.<ItemStack>getArgument(0))));
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    /** An item lying near the vein — dropped by {@code from}, or by anybody else when that is null. */
    private Item lying(ItemStack stack, BrokenBlock from) {
        Item item = mock(Item.class);
        UUID id = UUID.randomUUID();
        when(item.getUniqueId()).thenReturn(id);
        when(item.isValid()).thenReturn(true);
        when(item.getItemStack()).thenReturn(stack);
        ground.add(item);
        if (from != null) {
            from.addDropEntity(id);
        }
        return item;
    }

    private BrokenBlock block(VeinOperation vein, int x) {
        return vein.find(at(x)).orElseThrow();
    }

    private BlockKey at(int x) {
        return new BlockKey(worldId, x, 12, 0);
    }

    private VeinOperation vein(int blockCount, String drop) {
        for (int x = 0; x < blockCount; x++) {
            BrokenBlock broken = new BrokenBlock(at(x), ore);
            broken.addDrops(List.of(stack(drop, 1)));
            history.veinBlock(player.getUniqueId(), at(0), broken, 1_000);
        }
        return history.latest(player.getUniqueId(), 5_000, 60_000).orElseThrow();
    }

    private int inInventory(String kind) {
        int total = 0;
        for (ItemStack stack : storage) {
            if (stack != null && kind.equals(kinds.get(stack))) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Test
    @DisplayName("the whole vein goes back, paid for with the drops still lying on the ground and then the inventory")
    void wholeVein() {
        VeinOperation vein = vein(3, "diamond");
        Item onGround = lying(stack("diamond", 2), block(vein, 0));
        storage[0] = stack("diamond", 5);

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isEqualTo(3);
        assertThat(outcome.inTheWay()).isZero();
        assertThat(outcome.unpaid()).isZero();
        verify(onGround).remove();
        assertThat(inInventory("diamond")).isEqualTo(4);
        for (int x = 0; x < 3; x++) {
            verify(blocks.get(at(x))).setBlockData(ore, false);
            assertThat(restored.holds(at(x), ore)).isTrue();
        }
        assertThat(history.latest(player.getUniqueId(), 5_000, 60_000)).as("the vein is used up").isEmpty();
    }

    @Test
    @DisplayName("nothing to give back, nothing goes back — and the vein stays undoable for when they find it")
    void nothingToGiveBack() {
        VeinOperation vein = vein(2, "diamond");
        storage[0] = stack("dirt", 64);

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isZero();
        assertThat(outcome.unpaid()).isEqualTo(2);
        verify(blocks.get(at(0)), never()).setBlockData(any(BlockData.class), anyBoolean());
        assertThat(inInventory("dirt")).isEqualTo(64);
        assertThat(history.latest(player.getUniqueId(), 5_000, 60_000)).contains(vein);
    }

    @Test
    @DisplayName("part of the drops back, part of the vein back; the rest can be tried again")
    void part() {
        VeinOperation vein = vein(3, "diamond");
        storage[4] = stack("diamond", 2);

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isEqualTo(2);
        assertThat(outcome.unpaid()).isEqualTo(1);
        assertThat(inInventory("diamond")).isZero();
        assertThat(storage[4]).as("an emptied slot is emptied, not a stack of nothing").isNull();
        assertThat(history.latest(player.getUniqueId(), 5_000, 60_000).orElseThrow().blocks()).hasSize(1);
    }

    @Test
    @DisplayName("a block somebody built in the hole is never overwritten")
    void occupied() {
        VeinOperation vein = vein(2, "diamond");
        storage[0] = stack("diamond", 2);
        Block built = airAt(at(1));
        when(built.isReplaceable()).thenReturn(false);
        blocks.put(at(1), built);

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isEqualTo(1);
        assertThat(outcome.inTheWay()).isEqualTo(1);
        verify(built, never()).setBlockData(any(BlockData.class), anyBoolean());
        assertThat(inInventory("diamond")).as("nothing taken for the block left alone").isEqualTo(1);
    }

    @Test
    @DisplayName("a block in another region's hands is left alone on Folia")
    void otherRegion() {
        VeinOperation vein = vein(1, "diamond");
        storage[0] = stack("diamond", 1);
        when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(false);

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isZero();
        assertThat(outcome.inTheWay()).isEqualTo(1);
        assertThat(inInventory("diamond")).isEqualTo(1);
    }

    @Test
    @DisplayName("a stack lying on the ground is shrunk, not removed, when only part of it is owed")
    void shrinkGroundStack() {
        VeinOperation vein = vein(1, "diamond");
        ItemStack pile = stack("diamond", 5);
        Item onGround = lying(pile, block(vein, 0));

        service.undo(player, vein);

        verify(onGround, never()).remove();
        ArgumentCaptor<ItemStack> put = ArgumentCaptor.forClass(ItemStack.class);
        verify(onGround).setItemStack(put.capture());
        assertThat(put.getValue().getAmount()).isEqualTo(4);
    }

    @Test
    @DisplayName("somebody else's items lying near the vein are never taken to pay for it")
    void notOtherPeoplesItems() {
        VeinOperation vein = vein(1, "diamond");
        Item theirs = lying(stack("diamond", 3), null);

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isZero();
        assertThat(outcome.unpaid()).isEqualTo(1);
        verify(theirs, never()).remove();
        verify(theirs, never()).setItemStack(any());
    }

    @Test
    @DisplayName("nobody is walled in: a block whose space somebody stands in stays mined")
    void nobodyEntombed() {
        VeinOperation vein = vein(2, "diamond");
        storage[0] = stack("diamond", 2);
        org.bukkit.entity.Player standing = mock(org.bukkit.entity.Player.class);
        when(world.getNearbyEntities(any(org.bukkit.util.BoundingBox.class), any())).thenAnswer(call -> {
            org.bukkit.util.BoundingBox box = call.getArgument(0);
            return box.contains(1.5, 12.5, 0.5) ? List.of(standing) : List.of();
        });

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isEqualTo(1);
        assertThat(outcome.inTheWay()).isEqualTo(1);
        verify(blocks.get(at(1)), never()).setBlockData(any(BlockData.class), anyBoolean());
    }

    @Test
    @DisplayName("nothing goes back where the player may not build now")
    void protectedGround() {
        VeinOperation vein = vein(2, "diamond");
        storage[0] = stack("diamond", 2);
        protectedSpots.add(at(0));

        VeinUndoService.Outcome outcome = service.undo(player, vein);

        assertThat(outcome.restored()).isEqualTo(1);
        assertThat(outcome.inTheWay()).isEqualTo(1);
        assertThat(inInventory("diamond")).isEqualTo(1);
    }
}
