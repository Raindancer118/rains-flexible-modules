package de.raindancer.modules.veintoggle.store;

import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's last few veins, newest first, kept in memory only — an undo is for the vein you just
 * mined, not for last week's.
 *
 * <p>The block broken by hand is an ordinary break, and nothing about it says a vein will follow; so
 * each player's last one is held until Veinminer's first extra block names it as the source, and is
 * then made the first block of that vein.
 */
public final class VeinHistory {

    /** How many veins are kept per player. */
    public static final int KEPT = 5;

    /** How long a hand break may wait for the vein it started. Veinminer's delays are ticks, not seconds. */
    static final long HAND_BREAK_WAITS_MILLIS = 10_000L;

    private record HandBreak(BrokenBlock block, long at) {
    }

    private final UndoRule rule = new UndoRule();
    private final Map<UUID, Deque<VeinOperation>> veins = new ConcurrentHashMap<>();
    private final Map<UUID, HandBreak> lastByHand = new ConcurrentHashMap<>();

    public void brokeByHand(UUID player, BrokenBlock block, long now) {
        lastByHand.put(player, new HandBreak(block, now));
    }

    public void handDrops(UUID player, BlockKey at, Collection<ItemStack> drops) {
        HandBreak hand = lastByHand.get(player);
        if (hand != null && hand.block().at().equals(at)) {
            hand.block().addDrops(drops);
            return;
        }
        veinDrops(player, at, drops);
    }

    /**
     * One of Veinminer's extra blocks.
     *
     * @param source the block the vein started from, or null when Veinminer did not say
     */
    public void veinBlock(UUID player, BlockKey source, BrokenBlock block, long now) {
        Deque<VeinOperation> mine = veins.computeIfAbsent(player, nobody -> new ArrayDeque<>());
        synchronized (mine) {
            VeinOperation newest = mine.peekFirst();
            BlockKey from = source;
            if (from == null) {
                from = newest != null && !rule.settled(newest.changedAt(), now) ? newest.source() : block.at();
            }
            HandBreak hand = lastByHand.get(player);
            boolean startedHere = hand != null && hand.block().at().equals(from)
                    && now - hand.at() <= HAND_BREAK_WAITS_MILLIS;
            if (newest == null || !newest.source().equals(from) || startedHere) {
                newest = new VeinOperation(from, now);
                if (startedHere) {
                    newest.add(hand.block(), hand.at());
                    lastByHand.remove(player, hand);
                }
                mine.addFirst(newest);
                while (mine.size() > KEPT) {
                    mine.removeLast();
                }
            }
            newest.add(block, now);
        }
    }

    /** What Veinminer dropped for one of its blocks. */
    public void veinDrops(UUID player, BlockKey at, Collection<ItemStack> drops) {
        Deque<VeinOperation> mine = veins.get(player);
        if (mine == null) {
            return;
        }
        synchronized (mine) {
            for (VeinOperation vein : mine) {
                Optional<BrokenBlock> block = vein.find(at);
                if (block.isPresent()) {
                    block.get().addDrops(drops);
                    return;
                }
            }
        }
    }

    /** The newest vein still in time to be undone; older ones that ran out are let go on the way. */
    public Optional<VeinOperation> latest(UUID player, long now, long windowMillis) {
        Deque<VeinOperation> mine = veins.get(player);
        if (mine == null) {
            return Optional.empty();
        }
        synchronized (mine) {
            mine.removeIf(vein -> rule.expired(vein.changedAt(), now, windowMillis));
            return Optional.ofNullable(mine.peekFirst());
        }
    }

    public void remove(UUID player, VeinOperation vein) {
        Deque<VeinOperation> mine = veins.get(player);
        if (mine != null) {
            synchronized (mine) {
                mine.remove(vein);
            }
        }
    }

    public void forget(UUID player) {
        veins.remove(player);
        lastByHand.remove(player);
    }
}
