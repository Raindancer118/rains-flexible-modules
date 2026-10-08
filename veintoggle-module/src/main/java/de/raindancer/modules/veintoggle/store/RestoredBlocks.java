package de.raindancer.modules.veintoggle.store;

import de.raindancer.modules.veintoggle.model.BlockKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Blocks an undo put back, and what was taken for each. Broken again, such a block drops exactly that
 * and no experience: otherwise mine-undo-mine rerolls Fortune until it pays and farms the ore's XP.
 *
 * <p>Memory only. After a restart a put-back block drops like any other, which costs one roll of the
 * dice per block per restart — not worth a file.
 */
public final class RestoredBlocks {

    /** Beyond this the oldest marks go: a mark is a few objects, but nothing else ever clears an unmined one. */
    public static final int KEPT_AT_MOST = 20_000;

    private record Mark(BlockData data, List<ItemStack> drops) {
    }

    private final Map<BlockKey, Mark> marks = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, Mark> eldest) {
            return size() > KEPT_AT_MOST;
        }
    };

    public synchronized void mark(BlockKey at, BlockData data, List<ItemStack> drops) {
        marks.remove(at);
        marks.put(at, new Mark(data, List.copyOf(drops)));
    }

    /** Whether the block now at {@code at} is one an undo put back. */
    public synchronized boolean holds(BlockKey at, BlockData now) {
        Mark mark = marks.get(at);
        return mark != null && mark.data().equals(now);
    }

    /**
     * Takes the mark off and hands back what the block is to drop — only if the block is still the
     * one that was put back.
     */
    public synchronized Optional<List<ItemStack>> take(BlockKey at, BlockData now) {
        Mark mark = marks.remove(at);
        return mark != null && mark.data().equals(now) ? Optional.of(mark.drops()) : Optional.empty();
    }

    public synchronized int size() {
        return marks.size();
    }
}
