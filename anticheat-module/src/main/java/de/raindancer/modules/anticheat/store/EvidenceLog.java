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

    public Set<UUID> everybody() {
        return Set.copyOf(entries.keySet());
    }

    public String nameOf(UUID who) {
        return names.get(who);
    }

    public void clear(UUID who) {
        entries.remove(who);
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
            }
        });
        if (!written) {
            dirty.set(true);
        }
        return written;
    }
}
