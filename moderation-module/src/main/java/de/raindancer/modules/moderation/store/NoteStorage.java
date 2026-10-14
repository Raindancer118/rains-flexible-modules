package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.moderation.model.StaffNote;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The staff notes, on disk.
 *
 * <p>The same shape as {@link ReportStorage} and for the same reasons — one file, written whole,
 * through Core's {@link YamlStore}. Kept as its own class rather than sharing a generic one with
 * reports: the two records have nothing in common but their shape today, and a generic store would be
 * a parser with a strategy object where two thirty-line classes read straight through.
 *
 * <p><b>Never shown to the player it is about.</b> Nothing here enforces that — the screens do, behind
 * {@code ModerationPermission.NOTES} — but it is why the file is worth keeping apart from anything a
 * player can see.
 */
public final class NoteStorage {

    private static final LogChannel log = Log.of("moderation");

    /** The file this version writes. Branch on it rather than guessing when the shape changes. */
    public static final int DATA_VERSION = 1;

    private final YamlStore store;
    private final DirtyKeys dirty = new DirtyKeys();
    private NoteRegistry registry;
    private NetworkBus.Subscription following = () -> { };

    /** The network's notes: what staff write about a player on one server is there on every other. */
    public NoteStorage(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("notes.yml")));
    }

    NoteStorage(YamlStore store) {
        this.store = store;
    }

    /**
     * Keeps {@code registry} the notes the database holds: what this server changed is written alone, and
     * when another server changes them this server's changes go out first and theirs come in.
     */
    public synchronized void bind(NoteRegistry registry) {
        following.close();
        this.registry = registry;
        this.following = store.onChange(this::refresh);
    }

    public void close() {
        following.close();
    }

    /** A note this server wrote; written with the next {@link #flush()}. */
    public synchronized void put(StaffNote note) {
        registry.add(note);
        dirty.mark(note.id());
    }

    /**
     * Writes a new note under the next free id, taken in the database in the same step so two servers writing
     * at once never both get N7; only when the database cannot be reached is it numbered here and written later.
     */
    public StaffNote admit(Function<String, StaffNote> build) {
        AtomicReference<StaffNote> made = new AtomicReference<>();
        long above = registry.highest();
        boolean written = store.update(yaml -> {
            long next = Math.max(above, NumberedIds.highestIn(yaml.getConfigurationSection("notes"),
                    NoteRegistry.PREFIX)) + 1;
            StaffNote note = build.apply(NoteRegistry.PREFIX + next);
            write(yaml, note);
            made.set(note);
        });
        synchronized (this) {
            if (written) {
                registry.add(made.get());
                return made.get();
            }
            StaffNote offline = build.apply(registry.nextId());
            put(offline);
            return offline;
        }
    }

    /** A note this server took back; removed from the database with the next {@link #flush()}. */
    public synchronized void drop(String id) {
        registry.remove(id);
        dirty.mark(id);
    }

    /** Writes what this server changed. @return whether it reached the database */
    public synchronized boolean flush() {
        return dirty.write(store, (yaml, id) -> {
            registry.byId(id).ifPresentOrElse(note -> write(yaml, note), () -> yaml.set("notes." + id, null));
            yaml.set("version", DATA_VERSION);
        });
    }

    /** Writes what is changed, then takes the notes as the database holds them. */
    public synchronized void refresh() {
        if (flush()) {
            loadIfReadable().ifPresent(stored -> registry.replaceWith(stored, dirty::has));
        }
    }

    public Path file() {
        return store.file();
    }

    /** Everything on disk. An entry that will not read is skipped and named; the rest still load. */
    public List<StaffNote> load() {
        return loadIfReadable().orElseGet(ArrayList::new);
    }

    /** Empty when the document could not be read, which is not the same as having no notes. */
    public Optional<List<StaffNote>> loadIfReadable() {
        return store.readIfReadable().map(this::parse);
    }

    private List<StaffNote> parse(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("notes");
        List<StaffNote> notes = new ArrayList<>();
        if (root == null) {
            return notes;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null) {
                unreadable.add(id);
                continue;
            }
            try {
                notes.add(new StaffNote(id,
                        requiredUuid(entry.getString("subject")),
                        optionalUuid(entry.getString("author")),
                        entry.getString("author-name"),
                        entry.getString("text"),
                        instant(entry.getString("at"))));
            } catch (RuntimeException broken) {
                unreadable.add(id);
            }
        }
        if (!unreadable.isEmpty()) {
            log.error("{} staff note(s) could not be read and have been skipped: {}. The file was "
                    + "left untouched.", unreadable.size(), String.join(", ", unreadable));
        }
        return notes;
    }

    /** Writes these, leaving every other note as it is. @return whether it reached the database */
    public boolean saveAll(Collection<StaffNote> notes) {
        return store.update(yaml -> {
            yaml.set("version", DATA_VERSION);
            if (notes != null) {
                notes.forEach(note -> write(yaml, note));
            }
        });
    }

    private static void write(YamlConfiguration yaml, StaffNote note) {
        String at = "notes." + note.id();
        yaml.set(at + ".subject", note.subject().toString());
        yaml.set(at + ".author", note.author() == null ? null : note.author().toString());
        yaml.set(at + ".author-name", note.authorName());
        yaml.set(at + ".text", note.text());
        yaml.set(at + ".at", note.at().toString());
    }

    private static UUID optionalUuid(String text) {
        return text == null || text.isBlank() ? null : UUID.fromString(text);
    }

    private static UUID requiredUuid(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a note about nobody");
        }
        return UUID.fromString(text);
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a note with no time on it");
        }
        return Instant.parse(text);
    }
}
