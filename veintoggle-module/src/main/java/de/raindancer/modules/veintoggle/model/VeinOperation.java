package de.raindancer.modules.veintoggle.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One vein, as one undo: every block broken from the same source, in the order they went. Veinminer
 * breaks them over several ticks, on Folia from the region's thread, so this is filled while it may
 * also be read.
 */
public final class VeinOperation {

    private final BlockKey source;
    private final Map<BlockKey, BrokenBlock> blocks = new LinkedHashMap<>();
    private long changedAt;

    public VeinOperation(BlockKey source, long now) {
        this.source = source;
        this.changedAt = now;
    }

    public BlockKey source() {
        return source;
    }

    public synchronized void add(BrokenBlock block, long now) {
        blocks.put(block.at(), block);
        changedAt = Math.max(changedAt, now);
    }

    public synchronized Optional<BrokenBlock> find(BlockKey at) {
        return Optional.ofNullable(blocks.get(at));
    }

    public synchronized List<BrokenBlock> blocks() {
        return List.copyOf(blocks.values());
    }

    public synchronized void removeAll(Collection<BrokenBlock> done) {
        done.forEach(block -> blocks.remove(block.at(), block));
    }

    public synchronized boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** When the last block was added — a vein still falling is not undone half-way. */
    public synchronized long changedAt() {
        return changedAt;
    }
}
