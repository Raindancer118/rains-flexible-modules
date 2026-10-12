package de.raindancer.modules.anticheat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.anticheat.model.Evidence;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Every failed check, per player, newest kept; survives restarts in {@code evidence.yml}. */
public final class EvidenceLog {

    private final YamlStore store;
    private final Map<UUID, Deque<Evidence>> entries = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** When the anti-cheat started measuring a player afresh: what came before is history, not a measure. */
    private final Map<UUID, Long> measuredFrom = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile int capacity;

    public EvidenceLog(Path dataFolder, int capacity) {
        this.store = new YamlStore(dataFolder.resolve("evidence.yml"));
        this.capacity = Math.max(1, capacity);
    }

    public void capacity(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void add(UUID who, String name, Evidence evidence) {
        Deque<Evidence> theirs = entries.computeIfAbsent(who, ignored -> new ArrayDeque<>());
        synchronized (theirs) {
            theirs.addLast(evidence);
            while (theirs.size() > capacity) {
                theirs.removeFirst();
            }
        }
        if (name != null) {
            names.put(who, name);
        }
        dirty.set(true);
    }

    /** Newest first. */
    public List<Evidence> of(UUID who) {
        Deque<Evidence> theirs = entries.get(who);
        if (theirs == null) {
            return List.of();
        }
        synchronized (theirs) {
            List<Evidence> copy = new ArrayList<>(theirs);
            java.util.Collections.reverse(copy);
            return copy;
        }
    }

    /** Newest first, only what came after the last fresh start — what a measure of them is built on. */
    public List<Evidence> since(UUID who) {
        long from = measuredFrom(who);
        return of(who).stream().filter(entry -> entry.atMillis() >= from).toList();
    }

    /** From now on the evidence before {@code millis} is history: kept, shown, never counted. */
    public void measureFrom(UUID who, long millis) {
        measuredFrom.put(who, millis);
        dirty.set(true);
    }

    /** 0 when they were never started afresh. */
    public long measuredFrom(UUID who) {
        return measuredFrom.getOrDefault(who, 0L);
    }

    public Set<UUID> everybody() {
        return Set.copyOf(entries.keySet());
    }

    public String nameOf(UUID who) {
        return names.get(who);
    }

    public void clear(UUID who) {
        entries.remove(who);
        measuredFrom.remove(who);
        dirty.set(true);
    }

    public void load() {
        entries.clear();
        var root = store.read().getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                continue;
            }
            Deque<Evidence> theirs = new ArrayDeque<>();
            for (String line : root.getStringList(id + ".flags")) {
                Evidence.decode(line).ifPresent(theirs::addLast);
            }
            long from = root.getLong(id + ".measured-from", 0);
            if (from > 0) {
                measuredFrom.put(who, from);
            }
            String name = root.getString(id + ".name");
            if (name != null) {
                names.put(who, name);
            }
            if (!theirs.isEmpty()) {
                entries.put(who, theirs);
            }
        }
    }

    /** @return whether it reached the disk; nothing to write counts as reached */
    public boolean flush() {
        if (!dirty.getAndSet(false)) {
            return true;
        }
        boolean written = store.write(yaml -> {
            for (Map.Entry<UUID, Deque<Evidence>> entry : entries.entrySet()) {
                List<String> lines = new ArrayList<>();
                synchronized (entry.getValue()) {
                    entry.getValue().forEach(evidence -> lines.add(evidence.encode()));
                }
                String key = "players." + entry.getKey();
                yaml.set(key + ".name", names.getOrDefault(entry.getKey(), ""));
                yaml.set(key + ".flags", lines);
                Long from = measuredFrom.get(entry.getKey());
                if (from != null) {
                    yaml.set(key + ".measured-from", from);
                }
            }
            // Started afresh with no evidence yet: the date still has to survive.
            for (Map.Entry<UUID, Long> entry : measuredFrom.entrySet()) {
                if (!entries.containsKey(entry.getKey())) {
                    String key = "players." + entry.getKey();
                    yaml.set(key + ".name", names.getOrDefault(entry.getKey(), ""));
                    yaml.set(key + ".measured-from", entry.getValue());
                }
            }
        });
        if (!written) {
            dirty.set(true);
        }
        return written;
    }
}
