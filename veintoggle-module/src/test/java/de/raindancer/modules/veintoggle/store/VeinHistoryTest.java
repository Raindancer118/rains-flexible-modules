package de.raindancer.modules.veintoggle.store;

import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What each player's last veins broke and dropped, kept long enough to be undone. */
class VeinHistoryTest {

    private static final long WINDOW = 60_000L;

    private final UUID world = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final BlockData ore = mock(BlockData.class);
    private final VeinHistory history = new VeinHistory();

    private BlockKey at(int x) {
        return new BlockKey(world, x, 12, 0);
    }

    private BrokenBlock broken(int x) {
        return new BrokenBlock(at(x), ore);
    }

    @Test
    @DisplayName("blocks from one source make one vein, and the block broken by hand is part of it")
    void oneVein() {
        history.brokeByHand(player, broken(0), 1_000);
        ItemStack handDrop = mock(ItemStack.class);
        history.handDrops(player, at(0), List.of(handDrop), List.of());
        history.veinBlock(player, at(0), broken(1), 1_050);
        history.veinBlock(player, at(0), broken(2), 1_100);

        VeinOperation vein = history.latest(player, 2_000, WINDOW).orElseThrow();
        assertThat(vein.blocks()).extracting(BrokenBlock::at).containsExactly(at(0), at(1), at(2));
        assertThat(vein.find(at(0)).orElseThrow().drops()).containsExactly(handDrop);
        assertThat(vein.changedAt()).isEqualTo(1_100);
    }

    @Test
    @DisplayName("Veinminer's drops are put with the block that dropped them")
    void drops() {
        ItemStack diamond = mock(ItemStack.class);
        history.veinBlock(player, at(0), broken(1), 1_000);
        history.veinDrops(player, at(1), List.of(diamond));

        assertThat(history.latest(player, 1_500, WINDOW).orElseThrow().find(at(1)).orElseThrow().drops())
                .containsExactly(diamond);
    }

    @Test
    @DisplayName("a hand break somewhere else is not dragged into the next vein")
    void otherHandBreak() {
        history.brokeByHand(player, broken(50), 1_000);
        history.veinBlock(player, at(0), broken(1), 1_050);

        assertThat(history.latest(player, 2_000, WINDOW).orElseThrow().blocks())
                .extracting(BrokenBlock::at).containsExactly(at(1));
    }

    @Test
    @DisplayName("a new source starts a new vein; the newest is the one undone first")
    void newestFirst() {
        history.veinBlock(player, at(0), broken(1), 1_000);
        history.veinBlock(player, at(10), broken(11), 3_000);

        VeinOperation newest = history.latest(player, 4_000, WINDOW).orElseThrow();
        assertThat(newest.source()).isEqualTo(at(10));
        history.remove(player, newest);
        assertThat(history.latest(player, 4_000, WINDOW).orElseThrow().source()).isEqualTo(at(0));
    }

    @Test
    @DisplayName("an old vein is not undoable any more, and only the last few are kept")
    void bounded() {
        history.veinBlock(player, at(0), broken(1), 1_000);
        assertThat(history.latest(player, 1_000 + WINDOW + 1, WINDOW)).isEmpty();

        for (int vein = 0; vein < VeinHistory.KEPT + 3; vein++) {
            history.veinBlock(player, at(vein * 10), broken(vein * 10 + 1), 100_000 + vein);
        }
        int left = 0;
        while (history.latest(player, 100_100, WINDOW).isPresent()) {
            history.remove(player, history.latest(player, 100_100, WINDOW).orElseThrow());
            left++;
        }
        assertThat(left).isEqualTo(VeinHistory.KEPT);
    }

    @Test
    @DisplayName("somebody who left is forgotten")
    void forget() {
        history.brokeByHand(player, broken(0), 1_000);
        history.veinBlock(player, at(0), broken(1), 1_000);
        history.forget(player);
        assertThat(history.latest(player, 1_500, WINDOW)).isEmpty();
        history.veinBlock(player, at(0), broken(1), 1_600);
        assertThat(history.latest(player, 1_700, WINDOW).orElseThrow().blocks()).hasSize(1);
    }

    @Test
    @DisplayName("blocks and drops arriving from several region threads at once are all kept")
    void concurrent() throws InterruptedException {
        int threads = 8;
        int each = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < each; i++) {
                        int x = 1 + thread * each + i;
                        history.veinBlock(player, at(0), broken(x), 1_000);
                        history.veinDrops(player, at(x), List.of(mock(ItemStack.class)));
                        history.latest(player, 1_000, WINDOW);
                    }
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        VeinOperation vein = history.latest(player, 1_000, WINDOW).orElseThrow();
        assertThat(vein.blocks()).hasSize(threads * each);
        assertThat(vein.blocks()).allSatisfy(block -> assertThat(block.drops()).hasSize(1));
    }
}
