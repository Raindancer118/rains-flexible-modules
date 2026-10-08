package de.raindancer.modules.veintoggle.model;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private final Set<UUID> dropEntities = new LinkedHashSet<>();

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

    public synchronized void addDropEntity(UUID item) {
        dropEntities.add(item);
    }

    public synchronized Set<UUID> dropEntities() {
        return Set.copyOf(dropEntities);
    }
}
