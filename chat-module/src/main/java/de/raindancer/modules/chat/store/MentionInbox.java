package de.raindancer.modules.chat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mentions that arrived while somebody was away, kept until they are back — across restarts, since "while
 * you were away" usually spans one. A few per player: past that it is a backlog nobody reads.
 *
 * <p>One document for the whole network: a mention left on one server is delivered when the player joins
 * any server, and delivered once. Notes are entries keyed by time and content rather than one list per
 * player, so two servers leaving a note for the same player at once keep both. {@link #watch()} reloads
 * when another server writes; notes left or taken here and not yet written are kept across the reload.
 */
public final class MentionInbox {

    private static final int KEPT_PER_PLAYER = 10;

    /** Who said it, what, and when. */
    public record Note(String from, String text, long at) {
        String key() {
            return at + "-" + Integer.toHexString((from + "\u0000" + text).hashCode());
        }
    }

    private final YamlStore store;
    private final Map<UUID, List<Note>> waiting = new HashMap<>();
    /** Left here and not yet written, by entry key — kept when a reload replaces the rest. */
    private final Map<UUID, Map<String, Note>> added = new HashMap<>();
    /** Taken or dropped here and not yet written, by entry key — kept gone when a reload replaces the rest. */
    private final Map<UUID, Set<String>> removed = new HashMap<>();
    /** False until the document has been read once: writing before that could replace what we never saw. */
    private boolean loaded;
    private long lastAt;
    /** Serialises reading and writing the document: a write merges what others wrote, and memory must catch up before the next. */
    private final Object io = new Object();

    public MentionInbox(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("mentions.yml")));
    }

    public MentionInbox(YamlStore store) {
        this.store = store;
    }

    public synchronized void add(UUID player, String from, String text) {
        // Strictly increasing, so the order of two notes in one millisecond survives a trip through the document.
        lastAt = Math.max(System.currentTimeMillis(), lastAt + 1);
        Note note = new Note(from, text, lastAt);
        List<Note> notes = waiting.computeIfAbsent(player, id -> new ArrayList<>());
        notes.add(note);
        added.computeIfAbsent(player, id -> new LinkedHashMap<>()).put(note.key(), note);
        while (notes.size() > KEPT_PER_PLAYER) {
            forget(player, notes.removeFirst());
        }
    }

    private void forget(UUID player, Note note) {
        Map<String, Note> pending = added.get(player);
        if (pending != null) {
            pending.remove(note.key());
        }
        removed.computeIfAbsent(player, id -> new HashSet<>()).add(note.key());
    }

    public synchronized List<Note> waitingFor(UUID player) {
        List<Note> notes = waiting.get(player);
        return notes == null ? List.of() : List.copyOf(notes);
    }

    /** Everything waiting for {@code player}, which is then gone. */
    public synchronized List<Note> take(UUID player) {
        List<Note> notes = waiting.remove(player);
        if (notes == null) {
            return List.of();
        }
        notes.forEach(note -> forget(player, note));
        return List.copyOf(notes);
    }

    /**
     * Reads the document. Called when the module starts and when another server wrote it. A read that fails
     * leaves what is held alone; notes left or taken here and not yet written stay as they are.
     *
     * @return false when it could not be read
     */
    public boolean load() {
        synchronized (io) {
            return read();
        }
    }

    private boolean read() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            return false;
        }
        Map<UUID, List<Note>> fresh = new HashMap<>();
        ConfigurationSection players = read.get().getConfigurationSection("players");
        if (players != null) {
            for (String rawId : players.getKeys(false)) {
                UUID who;
                try {
                    who = UUID.fromString(rawId);
                } catch (IllegalArgumentException notAnId) {
                    continue;
                }
                List<Note> notes = new ArrayList<>();
                if (players.isList(rawId)) {
                    for (Map<?, ?> raw : players.getMapList(rawId)) {
                        note(raw.get("from"), raw.get("text"), raw.get("at")).ifPresent(notes::add);
                    }
                } else if (players.isConfigurationSection(rawId)) {
                    ConfigurationSection entries = players.getConfigurationSection(rawId);
                    for (String key : entries.getKeys(false)) {
                        ConfigurationSection entry = entries.getConfigurationSection(key);
                        if (entry != null) {
                            note(entry.get("from"), entry.get("text"), entry.get("at")).ifPresent(notes::add);
                        }
                    }
                }
                fresh.put(who, notes);
            }
        }
        synchronized (this) {
            removed.forEach((who, keys) -> {
                List<Note> notes = fresh.get(who);
                if (notes != null) {
                    notes.removeIf(note -> keys.contains(note.key()));
                }
            });
            added.forEach((who, pending) -> {
                List<Note> notes = fresh.computeIfAbsent(who, id -> new ArrayList<>());
                pending.forEach((key, note) -> {
                    if (notes.stream().noneMatch(note::equals)) {
                        notes.add(note);
                    }
                });
            });
            waiting.clear();
            fresh.forEach((who, notes) -> {
                notes.sort(Comparator.comparingLong(Note::at));
                while (notes.size() > KEPT_PER_PLAYER) {
                    notes.removeFirst();
                }
                if (!notes.isEmpty()) {
                    waiting.put(who, notes);
                }
            });
            loaded = true;
        }
        return true;
    }

    private static Optional<Note> note(Object from, Object text, Object at) {
        if (from == null || text == null) {
            return Optional.empty();
        }
        return Optional.of(new Note(from.toString(), text.toString(), at instanceof Number number ? number.longValue() : 0L));
    }

    /** Writes if anything changed; what other servers left in the document is kept. @return whether nothing is left unwritten */
    public boolean save() {
        synchronized (io) {
            return write();
        }
    }

    private boolean write() {
        Map<UUID, List<Note>> writing = new HashMap<>();
        Map<UUID, Set<String>> sentAdded = new HashMap<>();
        Map<UUID, Set<String>> sentRemoved = new HashMap<>();
        synchronized (this) {
            if (added.isEmpty() && removed.isEmpty()) {
                return true;
            }
        }
        // Never write over a document that was not read: it may hold what other servers left.
        if (!isLoaded() && !read()) {
            return false;
        }
        synchronized (this) {
            waiting.forEach((who, notes) -> writing.put(who, List.copyOf(notes)));
            added.forEach((who, pending) -> sentAdded.put(who, new HashSet<>(pending.keySet())));
            removed.forEach((who, keys) -> sentRemoved.put(who, new HashSet<>(keys)));
        }
        boolean written = store.write(yaml -> writing.forEach((who, notes) -> {
            for (Note note : notes) {
                String base = "players." + who + "." + note.key();
                yaml.set(base + ".from", note.from());
                yaml.set(base + ".text", note.text());
                yaml.set(base + ".at", note.at());
            }
        }));
        if (written) {
            synchronized (this) {
                sentAdded.forEach((who, keys) -> {
                    Map<String, Note> pending = added.get(who);
                    if (pending != null) {
                        pending.keySet().removeAll(keys);
                        if (pending.isEmpty()) {
                            added.remove(who);
                        }
                    }
                });
                sentRemoved.forEach((who, keys) -> {
                    Set<String> pending = removed.get(who);
                    if (pending != null) {
                        pending.removeAll(keys);
                        if (pending.isEmpty()) {
                            removed.remove(who);
                        }
                    }
                });
            }
            // The write merged what other servers wrote; the next must start from that, not from before it.
            read();
        }
        return written;
    }

    private synchronized boolean isLoaded() {
        return loaded;
    }

    /** Reloads when another server writes the document, after writing what this one holds. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(() -> {
            synchronized (io) {
                write();
                read();
            }
        });
    }
}
