package de.raindancer.modules.veintoggle.model;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One vein, as one undo: every block broken from the same source, in the order they went. Veinminer
 * breaks them over several ticks, on Folia from the region's thread, so this is filled while it may
 * also be read.
 *
 * <p>Also who picked up what it dropped — anybody, however many — so an undo can ask them for it back.
 */
public final class VeinOperation {

    private final UUID owner;
    private final BlockKey source;
    private final Map<BlockKey, BrokenBlock> blocks = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> collected = new LinkedHashMap<>();
    private long changedAt;

    public VeinOperation(UUID owner, BlockKey source, long now) {
        this.owner = owner;
        this.source = source;
        this.changedAt = now;
    }

    /** Who mined it. */
    public UUID owner() {
        return owner;
    }

    /** {@code who} picked up {@code amount} of {@code kind} that this vein dropped. */
    public synchronized void collected(UUID who, ItemStack kind, int amount) {
        if (amount <= 0) {
            return;
        }
        List<ItemStack> theirs = collected.computeIfAbsent(who, nobody -> new ArrayList<>());
        for (ItemStack held : theirs) {
            if (held.isSimilar(kind)) {
                held.setAmount(held.getAmount() + amount);
                return;
            }
        }
        ItemStack one = kind.clone();
        one.setAmount(amount);
        theirs.add(one);
    }

    /** {@code who} has settled {@code amount} of {@code kind} — given back or paid for. */
    public synchronized void settled(UUID who, ItemStack kind, int amount) {
        List<ItemStack> theirs = collected.get(who);
        if (theirs == null) {
            return;
        }
        for (ItemStack held : theirs) {
            if (held.isSimilar(kind)) {
                held.setAmount(Math.max(0, held.getAmount() - amount));
            }
        }
        theirs.removeIf(held -> held.getAmount() <= 0);
        if (theirs.isEmpty()) {
            collected.remove(who);
        }
    }

    /** Everybody who picked something up, and how much of each kind they still owe. */
    public synchronized Map<UUID, List<ItemStack>> collected() {
        Map<UUID, List<ItemStack>> copy = new LinkedHashMap<>();
        collected.forEach((who, stacks) -> copy.put(who, stacks.stream().map(ItemStack::clone).toList()));
        return copy;
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
