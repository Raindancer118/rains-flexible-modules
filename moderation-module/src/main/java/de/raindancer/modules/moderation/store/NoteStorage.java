package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.moderation.model.StaffNote;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

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

    public NoteStorage(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("notes.yml"));
    }

    public Path file() {
        return store.file();
    }

    /** Everything on disk. An entry that will not read is skipped and named; the rest still load. */
    public List<StaffNote> load() {
        ConfigurationSection root = store.read().getConfigurationSection("notes");
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

    /** Writes the lot. @return whether it reached the disk */
    public boolean saveAll(Collection<StaffNote> notes) {
        return store.write(yaml -> {
            yaml.set("version", DATA_VERSION);
            if (notes == null) {
                return;
            }
            for (StaffNote note : notes) {
                String at = "notes." + note.id();
                yaml.set(at + ".subject", note.subject().toString());
                yaml.set(at + ".author", note.author() == null ? null : note.author().toString());
                yaml.set(at + ".author-name", note.authorName());
                yaml.set(at + ".text", note.text());
                yaml.set(at + ".at", note.at().toString());
            }
        });
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
