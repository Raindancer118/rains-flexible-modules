package de.raindancer.modules.veintoggle.model;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One block a vein took: what stood there, and what it dropped. The drops arrive in a later event
 * than the break, so they are added afterwards.
 */
public final class BrokenBlock {

    private final BlockKey at;
    private final BlockData data;
    private final List<ItemStack> drops = new ArrayList<>();

    public BrokenBlock(BlockKey at, BlockData data) {
        this.at = at;
        this.data = data;
    }

    public BlockKey at() {
        return at;
    }

    public BlockData data() {
        return data;
    }

    public synchronized void addDrops(Collection<ItemStack> more) {
        drops.addAll(more);
    }

    public synchronized List<ItemStack> drops() {
        return List.copyOf(drops);
    }
}
