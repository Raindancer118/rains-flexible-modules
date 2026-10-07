package de.raindancer.modules.chat.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mentions that arrived while somebody was away, kept until they are back — across restarts, since "while
 * you were away" usually spans one. A few per player: past that it is a backlog nobody reads.
 */
public final class MentionInbox {

    private static final int KEPT_PER_PLAYER = 10;

    /** Who said it, what, and when. */
    public record Note(String from, String text, long at) {
    }

    private final YamlStore store;
    private final Map<UUID, List<Note>> waiting = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public MentionInbox(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("mentions.yml"));
    }

    public void add(UUID player, String from, String text) {
        List<Note> notes = waiting.computeIfAbsent(player, id -> new ArrayList<>());
        synchronized (notes) {
            notes.add(new Note(from, text, System.currentTimeMillis()));
            while (notes.size() > KEPT_PER_PLAYER) {
                notes.removeFirst();
            }
        }
        dirty = true;
    }

    public List<Note> waitingFor(UUID player) {
        List<Note> notes = waiting.get(player);
        if (notes == null) {
            return List.of();
        }
        synchronized (notes) {
            return List.copyOf(notes);
        }
    }

    /** Everything waiting for {@code player}, which is then gone. */
    public List<Note> take(UUID player) {
        List<Note> notes = waiting.remove(player);
        if (notes == null) {
            return List.of();
        }
        dirty = true;
        synchronized (notes) {
            return List.copyOf(notes);
        }
    }

    public void load() {
        waiting.clear();
        if (!store.exists()) {
            return;
        }
        ConfigurationSection players = store.read().getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String rawId : players.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(rawId);
            } catch (IllegalArgumentException notAnId) {
                continue;
            }
            List<Note> notes = new ArrayList<>();
            for (Map<?, ?> raw : players.getMapList(rawId)) {
                Object from = raw.get("from");
                Object text = raw.get("text");
                Object at = raw.get("at");
                if (from != null && text != null) {
                    notes.add(new Note(from.toString(), text.toString(),
                            at instanceof Number number ? number.longValue() : 0L));
                }
            }
            if (!notes.isEmpty()) {
                waiting.put(who, notes);
            }
        }
        dirty = false;
    }

    /** Writes if anything changed. */
    public void save() {
        if (!dirty) {
            return;
        }
        boolean written = store.write(yaml -> waiting.forEach((who, notes) -> {
            List<Map<String, Object>> raw = new ArrayList<>();
            synchronized (notes) {
                for (Note note : notes) {
                    raw.add(Map.of("from", note.from(), "text", note.text(), "at", note.at()));
                }
            }
            yaml.set("players." + who, raw);
        }));
        dirty = !written;
    }
}
