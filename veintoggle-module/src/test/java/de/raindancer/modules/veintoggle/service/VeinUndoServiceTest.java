package de.raindancer.modules.veintoggle.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Undoing a vein: every block that goes back is paid for with exactly what it dropped — from the
 * ground, the undoer, whoever picked it up (items, then money), and last the undoer's money if they
 * agree. Nothing taken that was not needed stays taken.
 */
class VeinUndoServiceTest {

    private static final Money DIAMOND_PRICE = Money.of(1_000);

    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final UUID worldId = UUID.randomUUID();
    private final BlockData ore = mock(BlockData.class);
    private final Map<ItemStack, String> kinds = new IdentityHashMap<>();
    private final Map<BlockKey, Block> blocks = new HashMap<>();
    private final List<Item> ground = new ArrayList<>();
    private final Set<BlockKey> protectedSpots = new HashSet<>();
    private final List<Runnable> later = new ArrayList<>();
    private final List<String> told = new ArrayList<>();
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, ItemStack[]> inventories = new HashMap<>();
    private final VeinHistory history = new VeinHistory();
    private final RestoredBlocks restored = new RestoredBlocks();
    private boolean withEconomy = true;

    private final Player undoer = player("Miner");
    private final VeinUndoService service = new VeinUndoService(server, new UndoRule(), history, restored,
            (who, where) -> !protectedSpots.contains(new BlockKey(worldId, where.getBlockX(), where.getBlockY(), where.getBlockZ())),
            new VeinUndoService.Threads() {
                @Override
                public void region(World in, BlockKey at, Runnable task) {
                    task.run();
                }

                @Override
                public void player(Player player, Runnable task, Runnable gone) {
                    task.run();
                }

                @Override
                public void later(long ticks, Runnable task) {
                    later.add(task);
                }
            },
            () -> withEconomy ? Optional.of(economy()) : Optional.empty(),
            stack -> "diamond".equals(kinds.get(stack)) ? Optional.of(DIAMOND_PRICE) : Optional.empty(),
            notices(), 60);

    VeinUndoServiceTest() {
        when(world.getUID()).thenReturn(worldId);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(true);
        when(server.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> blocks.computeIfAbsent(
                new BlockKey(worldId, call.getArgument(0), call.getArgument(1), call.getArgument(2)), this::airAt));
        when(world.getNearbyEntitiesByType(eq(Item.class), any(Location.class), anyDouble())).thenAnswer(call -> ground);
    }

    // ───────────────────────────────────────────────────────────── the world, faked

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        PlayerInventory inventory = mock(PlayerInventory.class);
        inventories.put(id, new ItemStack[36]);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenAnswer(call -> inventories.get(id).clone());
        doAnswer(call -> {
            inventories.put(id, call.getArgument(0));
            return null;
        }).when(inventory).setStorageContents(any());
        when(inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack[] slots = inventories.get(id);
            for (Object given : call.getArguments()) {
                for (int slot = 0; slot < slots.length; slot++) {
                    if (slots[slot] == null) {
                        slots[slot] = (ItemStack) given;
                        break;
                    }
                }
            }
            return new HashMap<Integer, ItemStack>();
        });
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private Economy economy() {
        return new Economy() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public Currency currency() {
                return null;
            }

            @Override
            public boolean hasAccount(UUID player) {
                return true;
            }

            @Override
            public Money balance(UUID player) {
                return Money.of(balances.getOrDefault(player, 0L));
            }

            @Override
            public EconomyResult deposit(UUID player, Money amount, String reason) {
                balances.merge(player, amount.minor(), Long::sum);
                return EconomyResult.done(amount, balance(player));
            }

            @Override
            public EconomyResult withdraw(UUID player, Money amount, String reason) {
                if (balances.getOrDefault(player, 0L) < amount.minor()) {
                    return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, balance(player));
                }
                balances.merge(player, -amount.minor(), Long::sum);
                return EconomyResult.done(amount, balance(player));
            }

            @Override
            public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String format(Money amount) {
                return "$" + amount.minor();
            }
        };
    }

    private VeinUndoService.Notices notices() {
        return new VeinUndoService.Notices() {
            @Override
            public void outcome(Player who, VeinUndoService.Outcome outcome) {
                told.add(who.getName() + " outcome " + outcome.restored() + "/" + outcome.inTheWay() + "/" + outcome.unpaid());
            }

            @Override
            public void tooFar(Player who) {
                told.add(who.getName() + " too far");
            }

            @Override
            public void bill(Player who, String amount, int items, long seconds) {
                told.add(who.getName() + " bill " + amount + " for " + items);
            }

            @Override
            public void cannotPayAll(Player who, String paid, String wanted) {
                told.add(who.getName() + " paid only " + paid + " of " + wanted);
            }

            @Override
            public void noBill(Player who) {
                told.add(who.getName() + " no bill");
            }

            @Override
            public void busy(Player who) {
                told.add(who.getName() + " busy");
            }

            @Override
            public void tookBack(Player collector, String from, int items) {
                told.add(collector.getName() + " gave back " + items + " to " + from);
            }

            @Override
            public void charged(Player collector, String from, String amount, int items) {
                told.add(collector.getName() + " charged " + amount + " for " + items + " by " + from);
            }
        };
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
        when(stack.clone()).thenAnswer(call -> stack(kind, count.get()));
        when(stack.getMaxStackSize()).thenReturn(64);
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
            from.addDropEntity(id, stack.getAmount());
        }
        return item;
    }

    private BlockKey at(int x) {
        return new BlockKey(worldId, x, 12, 0);
    }

    private VeinOperation vein(int blockCount, String drop) {
        for (int x = 0; x < blockCount; x++) {
            BrokenBlock broken = new BrokenBlock(at(x), ore);
            broken.addDrops(List.of(stack(drop, 1)));
            history.veinBlock(undoer.getUniqueId(), at(0), broken, 1_000);
        }
        return history.latest(undoer.getUniqueId(), 5_000, 60_000).orElseThrow();
    }

    private BrokenBlock block(VeinOperation vein, int x) {
        return vein.find(at(x)).orElseThrow();
    }

    private int carrying(Player player, String kind) {
        int total = 0;
        for (ItemStack stack : inventories.get(player.getUniqueId())) {
            if (stack != null && kind.equals(kinds.get(stack))) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void give(Player player, ItemStack stack) {
        ItemStack[] slots = inventories.get(player.getUniqueId());
        for (int slot = 0; slot < slots.length; slot++) {
            if (slots[slot] == null) {
                slots[slot] = stack;
                return;
            }
        }
    }

    private boolean putBack(int x) {
        return restored.holds(at(x), ore);
    }

    private Optional<VeinOperation> stillUndoable() {
        return history.latest(undoer.getUniqueId(), 5_000, 60_000);
    }

    // ───────────────────────────────────────────────────────────── the undoer's own

    @Test
    @DisplayName("the whole vein goes back, paid for with its drops still on the ground and then the undoer's inventory")
    void wholeVein() {
        VeinOperation vein = vein(3, "diamond");
        Item onGround = lying(stack("diamond", 2), block(vein, 0));
        give(undoer, stack("diamond", 5));

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 3/0/0");
        verify(onGround).remove();
        assertThat(carrying(undoer, "diamond")).isEqualTo(4);
        for (int x = 0; x < 3; x++) {
            verify(blocks.get(at(x))).setBlockData(ore, false);
            assertThat(putBack(x)).isTrue();
        }
        assertThat(stillUndoable()).as("the vein is used up").isEmpty();
    }

    @Test
    @DisplayName("without any money on the server, what nobody gives back stays mined — and stays undoable")
    void nothingToGiveBack() {
        withEconomy = false;
        VeinOperation vein = vein(2, "diamond");
        give(undoer, stack("dirt", 64));

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 0/0/2");
        verify(blocks.get(at(0)), never()).setBlockData(any(BlockData.class), anyBoolean());
        assertThat(carrying(undoer, "dirt")).isEqualTo(64);
        assertThat(stillUndoable()).contains(vein);
    }

    @Test
    @DisplayName("a stack lying on the ground is shrunk, not removed, when only part of it is owed")
    void shrinkGroundStack() {
        VeinOperation vein = vein(1, "diamond");
        ItemStack pile = stack("diamond", 5);
        Item onGround = lying(pile, block(vein, 0));
        block(vein, 0).takeFrom(onGround.getUniqueId(), 4);
        block(vein, 0).addDropEntity(onGround.getUniqueId(), 4);

        service.start(undoer, vein);

        verify(onGround, never()).remove();
        ArgumentCaptor<ItemStack> put = ArgumentCaptor.forClass(ItemStack.class);
        verify(onGround).setItemStack(put.capture());
        assertThat(put.getValue().getAmount()).isEqualTo(4);
    }

    @Test
    @DisplayName("somebody else's items lying near the vein are never taken to pay for it")
    void notOtherPeoplesItems() {
        withEconomy = false;
        VeinOperation vein = vein(1, "diamond");
        Item theirs = lying(stack("diamond", 3), null);

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 0/0/1");
        verify(theirs, never()).remove();
        verify(theirs, never()).setItemStack(any());
    }

    // ───────────────────────────────────────────────────────────── where blocks may not go

    @Test
    @DisplayName("a block somebody built in the hole is never overwritten, and nothing is taken for it")
    void occupied() {
        VeinOperation vein = vein(2, "diamond");
        give(undoer, stack("diamond", 2));
        Block built = airAt(at(1));
        when(built.isReplaceable()).thenReturn(false);
        blocks.put(at(1), built);

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 1/1/0");
        verify(built, never()).setBlockData(any(BlockData.class), anyBoolean());
        assertThat(carrying(undoer, "diamond")).isEqualTo(1);
    }

    @Test
    @DisplayName("nobody is walled in: a block whose space somebody stands in stays mined")
    void nobodyEntombed() {
        VeinOperation vein = vein(2, "diamond");
        give(undoer, stack("diamond", 2));
        Player standing = mock(Player.class);
        when(world.getNearbyEntities(any(BoundingBox.class), any())).thenAnswer(call -> {
            BoundingBox box = call.getArgument(0);
            return box.contains(1.5, 12.5, 0.5) ? List.of(standing) : List.of();
        });

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 1/1/0");
        verify(blocks.get(at(1)), never()).setBlockData(any(BlockData.class), anyBoolean());
    }

    @Test
    @DisplayName("nothing goes back where the player may not build now")
    void protectedGround() {
        VeinOperation vein = vein(2, "diamond");
        give(undoer, stack("diamond", 2));
        protectedSpots.add(at(0));

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 1/1/0");
        assertThat(carrying(undoer, "diamond")).isEqualTo(1);
    }

    @Test
    @DisplayName("a block in another region's hands is left alone on Folia")
    void otherRegion() {
        VeinOperation vein = vein(1, "diamond");
        give(undoer, stack("diamond", 1));
        when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(false);

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 0/1/0");
        assertThat(carrying(undoer, "diamond")).isEqualTo(1);
    }

    @Test
    @DisplayName("an undoer who walked out of the vein's region is told to go back, and nothing is touched")
    void undoerElsewhere() {
        VeinOperation vein = vein(1, "diamond");
        give(undoer, stack("diamond", 1));
        when(server.isOwnedByCurrentRegion(undoer)).thenReturn(false);

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner too far");
        assertThat(carrying(undoer, "diamond")).isEqualTo(1);
    }

    // ───────────────────────────────────────────────────────────── collectors

    @Test
    @DisplayName("whoever picked up the drops gives them back out of their inventory, and is told")
    void collectorGivesBack() {
        VeinOperation vein = vein(3, "diamond");
        Player ada = player("Ada");
        Player bob = player("Bob");
        vein.collected(ada.getUniqueId(), stack("diamond", 1), 2);
        vein.collected(bob.getUniqueId(), stack("diamond", 1), 1);
        give(ada, stack("diamond", 5));
        give(bob, stack("diamond", 1));

        service.start(undoer, vein);

        assertThat(told).containsExactly("Ada gave back 2 to Miner", "Bob gave back 1 to Miner", "Miner outcome 3/0/0");
        assertThat(carrying(ada, "diamond")).as("only what they picked up").isEqualTo(3);
        assertThat(carrying(bob, "diamond")).isZero();
        assertThat(vein.collected()).isEmpty();
    }

    @Test
    @DisplayName("a collector who no longer has the items pays their shop price instead; an offline one pays from the bank")
    void collectorPays() {
        VeinOperation vein = vein(3, "diamond");
        Player ada = player("Ada");
        Player gone = player("Gone");
        when(server.getPlayer(gone.getUniqueId())).thenReturn(null);
        vein.collected(ada.getUniqueId(), stack("diamond", 1), 2);
        vein.collected(gone.getUniqueId(), stack("diamond", 1), 1);
        give(ada, stack("diamond", 1));
        give(gone, stack("diamond", 9));
        balances.put(ada.getUniqueId(), 5_000L);
        balances.put(gone.getUniqueId(), 1_500L);

        service.start(undoer, vein);

        assertThat(told).contains("Ada gave back 1 to Miner", "Ada charged $1000 for 1 by Miner", "Miner outcome 3/0/0");
        assertThat(balances.get(ada.getUniqueId())).isEqualTo(4_000L);
        assertThat(balances.get(gone.getUniqueId())).as("offline: the bank, not the inventory").isEqualTo(500L);
        assertThat(carrying(gone, "diamond")).isEqualTo(9);
        assertThat(balances.get(undoer.getUniqueId())).as("money taken is paid to nobody").isNull();
    }

    @Test
    @DisplayName("a collector who gave back part of it in items pays for exactly the rest")
    void collectorPaysTheRest() {
        VeinOperation vein = vein(5, "diamond");
        Player ada = player("Ada");
        vein.collected(ada.getUniqueId(), stack("diamond", 1), 5);
        give(ada, stack("diamond", 3));
        balances.put(ada.getUniqueId(), 10_000L);

        service.start(undoer, vein);

        assertThat(told).containsExactly("Ada gave back 3 to Miner", "Ada charged $2000 for 2 by Miner", "Miner outcome 5/0/0");
        assertThat(balances.get(ada.getUniqueId())).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("the undoer is never charged as a collector of their own vein")
    void undoerIsNoCollector() {
        VeinOperation vein = vein(1, "diamond");
        vein.collected(undoer.getUniqueId(), stack("diamond", 1), 1);
        balances.put(undoer.getUniqueId(), 5_000L);

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner bill $1000 for 1");
        assertThat(balances.get(undoer.getUniqueId())).as("nothing without agreeing").isEqualTo(5_000L);
    }

    // ───────────────────────────────────────────────────────────── the undoer's bill

    @Test
    @DisplayName("what nobody could cover is billed to the undoer, and paying it puts the whole vein back")
    void billPaid() {
        VeinOperation vein = vein(2, "diamond");
        Player ada = player("Ada");
        vein.collected(ada.getUniqueId(), stack("diamond", 1), 2);
        give(ada, stack("diamond", 1));
        balances.put(undoer.getUniqueId(), 3_000L);

        service.start(undoer, vein);
        assertThat(told).containsExactly("Miner bill $1000 for 1");
        assertThat(putBack(0)).as("nothing back before the bill is settled").isFalse();

        service.pay(undoer);

        assertThat(told).contains("Ada gave back 1 to Miner", "Miner outcome 2/0/0");
        assertThat(balances.get(undoer.getUniqueId())).isEqualTo(2_000L);
        assertThat(balances.get(ada.getUniqueId())).as("Ada had nothing left to pay with").isNull();
        assertThat(putBack(0)).isTrue();
        assertThat(putBack(1)).isTrue();
    }

    @Test
    @DisplayName("declining the bill puts back only what is covered, and what the rest needed goes back to whoever gave it")
    void billDeclined() {
        BrokenBlock big = new BrokenBlock(at(0), ore);
        big.addDrops(List.of(stack("diamond", 2)));
        history.veinBlock(undoer.getUniqueId(), at(0), big, 1_000);
        VeinOperation vein = stillUndoable().orElseThrow();
        Player ada = player("Ada");
        vein.collected(ada.getUniqueId(), stack("diamond", 1), 2);
        give(ada, stack("diamond", 1));

        service.start(undoer, vein);
        assertThat(carrying(ada, "diamond")).as("taken while the bill waits").isZero();
        service.decline(undoer);

        assertThat(told).containsExactly("Miner bill $1000 for 1", "Miner outcome 0/0/1");
        assertThat(carrying(ada, "diamond")).as("given back: the block stayed mined").isEqualTo(1);
        assertThat(vein.collected().get(ada.getUniqueId())).as("still owed for a later try").hasSize(1);
        assertThat(stillUndoable()).contains(vein);
    }

    @Test
    @DisplayName("a bill nobody answers runs out and settles as declined")
    void billExpires() {
        VeinOperation vein = vein(1, "diamond");
        balances.put(undoer.getUniqueId(), 5_000L);

        service.start(undoer, vein);
        assertThat(later).hasSize(1);
        later.getFirst().run();

        assertThat(told).containsExactly("Miner bill $1000 for 1", "Miner outcome 0/0/1");
        assertThat(balances.get(undoer.getUniqueId())).isEqualTo(5_000L);
        service.pay(undoer);
        assertThat(told).last().isEqualTo("Miner no bill");
    }

    @Test
    @DisplayName("an undoer who cannot afford it all pays what completes blocks; what completes none comes back")
    void billPartlyPaid() {
        BrokenBlock big = new BrokenBlock(at(0), ore);
        big.addDrops(List.of(stack("diamond", 2)));
        BrokenBlock small = new BrokenBlock(at(1), ore);
        small.addDrops(List.of(stack("diamond", 1)));
        history.veinBlock(undoer.getUniqueId(), at(0), small, 1_000);
        history.veinBlock(undoer.getUniqueId(), at(0), big, 1_000);
        VeinOperation vein = stillUndoable().orElseThrow();
        balances.put(undoer.getUniqueId(), 2_500L);

        service.start(undoer, vein);
        service.pay(undoer);

        assertThat(told).containsExactly("Miner bill $3000 for 3", "Miner paid only $2000 of $3000", "Miner outcome 1/0/1");
        assertThat(balances.get(undoer.getUniqueId())).as("paid 2, one refunded: the big block stayed mined")
                .isEqualTo(1_500L);
        assertThat(putBack(1)).isTrue();
        assertThat(putBack(0)).isFalse();
    }

    @Test
    @DisplayName("one undo at a time: a second while the first waits on its bill is refused")
    void busy() {
        VeinOperation vein = vein(1, "diamond");
        balances.put(undoer.getUniqueId(), 5_000L);

        service.start(undoer, vein);
        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner bill $1000 for 1", "Miner busy");
    }

    @Test
    @DisplayName("items with no price cannot be paid for with money, so their block stays mined")
    void unpriced() {
        VeinOperation vein = vein(1, "coal");
        Player ada = player("Ada");
        vein.collected(ada.getUniqueId(), stack("coal", 1), 1);
        balances.put(ada.getUniqueId(), 5_000L);
        balances.put(undoer.getUniqueId(), 5_000L);

        service.start(undoer, vein);

        assertThat(told).containsExactly("Miner outcome 0/0/1");
        assertThat(balances.get(ada.getUniqueId())).isEqualTo(5_000L);
    }
}
