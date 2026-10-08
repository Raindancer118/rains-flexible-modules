package de.raindancer.modules.veintoggle.store;

import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Each player's last few veins, newest first, kept in memory only — an undo is for the vein you just
 * mined, not for last week's.
 *
 * <p>The block broken by hand is an ordinary break, and nothing about it says a vein will follow; so
 * each player's last one is held until Veinminer's first extra block names it as the source, and is
 * then made the first block of that vein.
 *
 * <p>The item entities a vein dropped are indexed, so a merge or a pickup anywhere on the server can be
 * followed back to the vein, and a pickup written down as owed by whoever picked it up.
 */
public final class VeinHistory {

    /** How many veins are kept per player. */
    public static final int KEPT = 5;

    /** How long a hand break may wait for the vein it started. Veinminer's delays are ticks, not seconds. */
    static final long HAND_BREAK_WAITS_MILLIS = 10_000L;

    private record HandBreak(BrokenBlock block, long at) {
    }

    /** Where some of an item entity's contents came from. */
    private record Where(VeinOperation vein, BrokenBlock block) {
    }

    private final UndoRule rule = new UndoRule();
    private final Map<UUID, Deque<VeinOperation>> veins = new ConcurrentHashMap<>();
    private final Map<UUID, HandBreak> lastByHand = new ConcurrentHashMap<>();
    private final Map<UUID, List<Where>> entities = new ConcurrentHashMap<>();

    public void brokeByHand(UUID player, BrokenBlock block, long now) {
        lastByHand.put(player, new HandBreak(block, now));
    }

    /** What a hand break dropped, and the item entities it became with how many in each. */
    public void handDrops(UUID player, BlockKey at, Collection<ItemStack> drops, Map<UUID, Integer> spawned) {
        HandBreak hand = lastByHand.get(player);
        if (hand != null && hand.block().at().equals(at)) {
            hand.block().addDrops(drops);
            spawned.forEach(hand.block()::addDropEntity);
            return;
        }
        veinDrops(player, at, drops);
        spawned.forEach((entity, amount) -> veinDropEntity(player, at, entity, amount));
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
                newest = new VeinOperation(player, from, now);
                if (startedHere) {
                    newest.add(hand.block(), hand.at());
                    index(newest, hand.block());
                    lastByHand.remove(player, hand);
                }
                mine.addFirst(newest);
                while (mine.size() > KEPT) {
                    unindex(mine.removeLast());
                }
            }
            newest.add(block, now);
            index(newest, block);
        }
    }

    /** What Veinminer dropped for one of its blocks. */
    public void veinDrops(UUID player, BlockKey at, Collection<ItemStack> drops) {
        find(player, at).ifPresent(where -> where.block().addDrops(drops));
    }

    /** One of the item entities a vein block's drops became, holding {@code amount} of them. */
    public void veinDropEntity(UUID player, BlockKey at, UUID entity, int amount) {
        find(player, at).ifPresent(where -> {
            where.block().addDropEntity(entity, amount);
            entities.computeIfAbsent(entity, nobody -> new CopyOnWriteArrayList<>()).add(where);
        });
    }

    /** One item entity merged into another: what was the vein's in the one now lies in the other. */
    public void merged(UUID from, UUID into) {
        List<Where> moved = entities.remove(from);
        if (moved == null) {
            return;
        }
        for (Where where : moved) {
            int amount = where.block().takeFrom(from, Integer.MAX_VALUE);
            where.block().addDropEntity(into, amount);
            entities.computeIfAbsent(into, nobody -> new CopyOnWriteArrayList<>()).add(where);
        }
    }

    /**
     * {@code who} picked up {@code amount} of {@code kind} from {@code entity}. Whatever of it was a
     * vein's is written down as theirs to give back — unless they mined that vein themselves.
     */
    public void pickedUp(UUID entity, UUID who, ItemStack kind, int amount) {
        List<Where> from = entities.get(entity);
        if (from == null) {
            return;
        }
        int left = amount;
        for (Where where : from) {
            int taken = where.block().takeFrom(entity, left);
            left -= taken;
            if (!who.equals(where.vein().owner())) {
                where.vein().collected(who, kind, taken);
            }
        }
        from.removeIf(where -> where.block().lyingIn(entity) == 0);
        if (from.isEmpty()) {
            entities.remove(entity, from);
        }
    }

    /** The source of the vein a block belongs to. */
    public Optional<BlockKey> sourceOf(UUID player, BlockKey at) {
        return find(player, at).map(where -> where.vein().source());
    }

    private Optional<Where> find(UUID player, BlockKey at) {
        Deque<VeinOperation> mine = veins.get(player);
        if (mine == null) {
            return Optional.empty();
        }
        synchronized (mine) {
            for (VeinOperation vein : mine) {
                Optional<BrokenBlock> block = vein.find(at);
                if (block.isPresent()) {
                    return Optional.of(new Where(vein, block.get()));
                }
            }
        }
        return Optional.empty();
    }

    private void index(VeinOperation vein, BrokenBlock block) {
        block.dropEntities().keySet().forEach(entity ->
                entities.computeIfAbsent(entity, nobody -> new CopyOnWriteArrayList<>()).add(new Where(vein, block)));
    }

    private void unindex(VeinOperation vein) {
        entities.values().forEach(list -> list.removeIf(where -> where.vein() == vein));
        entities.values().removeIf(List::isEmpty);
    }

    /** The newest vein still in time to be undone; older ones that ran out are let go on the way. */
    public Optional<VeinOperation> latest(UUID player, long now, long windowMillis) {
        Deque<VeinOperation> mine = veins.get(player);
        if (mine == null) {
            return Optional.empty();
        }
        synchronized (mine) {
            mine.removeIf(vein -> {
                boolean gone = rule.expired(vein.changedAt(), now, windowMillis);
                if (gone) {
                    unindex(vein);
                }
                return gone;
            });
            return Optional.ofNullable(mine.peekFirst());
        }
    }

    public void remove(UUID player, VeinOperation vein) {
        Deque<VeinOperation> mine = veins.get(player);
        if (mine != null) {
            synchronized (mine) {
                if (mine.remove(vein)) {
                    unindex(vein);
                }
            }
        }
    }

    public void forget(UUID player) {
        Deque<VeinOperation> gone = veins.remove(player);
        if (gone != null) {
            synchronized (gone) {
                gone.forEach(this::unindex);
            }
        }
        lastByHand.remove(player);
    }
}
