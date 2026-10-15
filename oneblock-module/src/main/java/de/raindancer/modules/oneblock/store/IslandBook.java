package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.model.Island;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Every island in memory, the one place they are changed. A change is a function from the island to its next
 * version, applied under one lock, so two changes never both read the same shards; what changed is written behind,
 * by {@link #flush()} off the server's threads, latest version only.
 *
 * <p>One island per player is kept here, at the change: a change that would put somebody on a second island is
 * refused whatever path asked for it.
 */
public final class IslandBook {

    public enum Outcome { DONE, REFUSED, NOT_FOUND, ON_ANOTHER_ISLAND }

    public record Changed(Outcome outcome, Optional<Island> island) {

        public boolean done() {
            return outcome == Outcome.DONE;
        }
    }

    private final IslandStorage storage;
    private final Object lock = new Object();
    private final Map<UUID, Island> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> byPart = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> byPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final Set<UUID> archiving = new LinkedHashSet<>();
    /** What {@link #archive} took off, until it is written. */
    private final Map<UUID, Island> archived = new HashMap<>();

    public IslandBook(IslandStorage storage) {
        this.storage = storage;
    }

    /** Fills the book from the store. Blocking. False when the store could not be read — the book stays empty. */
    public boolean load() {
        Optional<List<Island>> loaded = storage.loadAll();
        if (loaded.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            for (Island island : loaded.get()) {
                index(island);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ reading — any thread, never blocks

    public Optional<Island> get(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Island> ofPlayer(UUID player) {
        UUID island = byPlayer.get(player);
        return island == null ? Optional.empty() : get(island);
    }

    /** The island a world belongs to, by the id part of its name. */
    public Optional<Island> ofWorld(String world) {
        return Island.idPartOf(world).map(byPart::get).flatMap(this::get);
    }

    public Collection<Island> all() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    // ------------------------------------------------------------------ changing

    public Changed add(Island island) {
        synchronized (lock) {
            if (byId.containsKey(island.id())) {
                return new Changed(Outcome.REFUSED, Optional.empty());
            }
            for (UUID player : island.everyone()) {
                if (byPlayer.containsKey(player)) {
                    return new Changed(Outcome.ON_ANOTHER_ISLAND, Optional.empty());
                }
            }
            index(island);
            dirty.add(island.id());
            return new Changed(Outcome.DONE, Optional.of(island));
        }
    }

    /** @param change the next version, or empty to refuse; it may run while other threads wait, so keep it short */
    public Changed change(UUID id, Function<Island, Optional<Island>> change) {
        synchronized (lock) {
            Island before = byId.get(id);
            if (before == null) {
                return new Changed(Outcome.NOT_FOUND, Optional.empty());
            }
            Optional<Island> after = change.apply(before);
            if (after.isEmpty()) {
                return new Changed(Outcome.REFUSED, Optional.of(before));
            }
            Island next = after.get();
            if (!next.id().equals(id)) {
                throw new IllegalArgumentException("a change may not turn one island into another");
            }
            for (UUID player : next.everyone()) {
                UUID on = byPlayer.get(player);
                if (on != null && !on.equals(id)) {
                    return new Changed(Outcome.ON_ANOTHER_ISLAND, Optional.of(before));
                }
            }
            unindex(before);
            index(next);
            dirty.add(id);
            return new Changed(Outcome.DONE, Optional.of(next));
        }
    }

    /** Off the book and the member index; the row and the worlds stay. */
    public Optional<Island> archive(UUID id) {
        synchronized (lock) {
            Island gone = byId.get(id);
            if (gone == null) {
                return Optional.empty();
            }
            unindex(gone);
            dirty.remove(id);
            archiving.add(id);
            archived.put(id, gone);
            return Optional.of(gone);
        }
    }

    private void index(Island island) {
        byId.put(island.id(), island);
        byPart.put(island.idPart(), island.id());
        island.everyone().forEach(player -> byPlayer.put(player, island.id()));
    }

    private void unindex(Island island) {
        byId.remove(island.id());
        byPart.remove(island.idPart(), island.id());
        island.everyone().forEach(player -> byPlayer.remove(player, island.id()));
    }

    // ------------------------------------------------------------------ writing behind

    /**
     * Writes what changed since the last flush, each island as it is now. Blocking — call it off the server's
     * threads. What could not be written stays to be tried again.
     *
     * @return how many writes failed
     */
    public int flush() {
        List<UUID> saving;
        List<Island> leaving = new ArrayList<>();
        synchronized (lock) {
            saving = new ArrayList<>(dirty);
            dirty.clear();
            for (UUID id : archiving) {
                leaving.add(archived.get(id));
            }
            archiving.clear();
        }
        int failed = 0;
        for (UUID id : saving) {
            Island now = byId.get(id);
            if (now != null && !storage.save(now)) {
                failed++;
                synchronized (lock) {
                    dirty.add(id);
                }
            }
        }
        for (Island gone : leaving) {
            if (!storage.archive(gone)) {
                failed++;
                synchronized (lock) {
                    archiving.add(gone.id());
                }
            } else {
                synchronized (lock) {
                    archived.remove(gone.id());
                }
            }
        }
        return failed;
    }

    public boolean hasUnwritten() {
        synchronized (lock) {
            return !dirty.isEmpty() || !archiving.isEmpty();
        }
    }
}
