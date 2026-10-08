package de.raindancer.modules.veintoggle.store;

import de.raindancer.modules.veintoggle.model.BlockKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** A block an undo put back drops exactly what was taken for it, once. */
class RestoredBlocksTest {

    private final BlockKey at = new BlockKey(UUID.randomUUID(), 1, 2, 3);
    private final BlockData ore = mock(BlockData.class);
    private final BlockData stone = mock(BlockData.class);
    private final ItemStack diamond = mock(ItemStack.class);
    private final RestoredBlocks restored = new RestoredBlocks();

    @Test
    @DisplayName("broken again, it gives back what was taken for it, and only the first time")
    void once() {
        restored.mark(at, ore, List.of(diamond));

        assertThat(restored.holds(at, ore)).isTrue();
        assertThat(restored.take(at, ore)).contains(List.of(diamond));
        assertThat(restored.holds(at, ore)).isFalse();
        assertThat(restored.take(at, ore)).isEmpty();
    }

    @Test
    @DisplayName("once something else stands there, the mark no longer applies and is dropped")
    void replaced() {
        restored.mark(at, ore, List.of(diamond));

        assertThat(restored.holds(at, stone)).isFalse();
        assertThat(restored.take(at, stone)).isEmpty();
        assertThat(restored.take(at, ore)).as("taken off when it did not match").isEmpty();
    }

    @Test
    @DisplayName("it cannot grow without end")
    void bounded() {
        for (int i = 0; i < RestoredBlocks.KEPT_AT_MOST + 10; i++) {
            restored.mark(new BlockKey(at.world(), i, 0, 0), ore, List.of());
        }
        assertThat(restored.size()).isEqualTo(RestoredBlocks.KEPT_AT_MOST);
        assertThat(restored.holds(new BlockKey(at.world(), 0, 0, 0), ore)).as("the oldest went first").isFalse();
    }
}
