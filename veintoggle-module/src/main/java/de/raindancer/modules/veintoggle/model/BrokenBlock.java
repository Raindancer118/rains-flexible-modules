package de.raindancer.modules.veintoggle.model;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One block a vein took: what stood there, what it dropped, and the item entities those drops became —
 * an undo takes back from the ground only those, never somebody else's items lying nearby. The drops
 * arrive in later events than the break, so they are added afterwards.
 */
public final class BrokenBlock {

    private final BlockKey at;
    private final BlockData data;
    private final List<ItemStack> drops = new ArrayList<>();
    /** Item entity → how many of this block's drops lie in it; a merge moves them, a pickup takes them. */
    private final Map<UUID, Integer> dropEntities = new LinkedHashMap<>();

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

    public synchronized void addDropEntity(UUID item, int amount) {
        if (amount > 0) {
            dropEntities.merge(item, amount, Integer::sum);
        }
    }

    /** How many of this block's drops lie in {@code item}. */
    public synchronized int lyingIn(UUID item) {
        return dropEntities.getOrDefault(item, 0);
    }

    /** Takes up to {@code amount} of them out of {@code item}. @return how many were there to take */
    public synchronized int takeFrom(UUID item, int amount) {
        int there = dropEntities.getOrDefault(item, 0);
        int taken = Math.min(there, Math.max(0, amount));
        if (there - taken <= 0) {
            dropEntities.remove(item);
        } else {
            dropEntities.put(item, there - taken);
        }
        return taken;
    }

    public synchronized Map<UUID, Integer> dropEntities() {
        return Map.copyOf(dropEntities);
    }
}
