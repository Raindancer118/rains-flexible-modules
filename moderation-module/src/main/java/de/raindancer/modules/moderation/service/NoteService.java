package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.StaffNote;
import de.raindancer.modules.moderation.store.NoteRegistry;
import de.raindancer.modules.moderation.store.NoteStorage;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The staff notes about a player.
 *
 * <p>The quiet half of a record: most of what staff need to remember about somebody is not a
 * punishment, and written into a punishment's reason it would be one they never received — and would
 * count towards their next.
 *
 * <p><b>A note is never shown to the player it is about.</b> Nothing here enforces that; the screens and
 * the commands do, behind {@code ModerationPermission.NOTES}. It is why a note can be honest, and why
 * removing one is worth an audit line of its own.
 */
public final class NoteService implements IModerationService {

    private final Plugin plugin;
    private final NoteRegistry notes;
    private final NoteStorage storage;
    private final Audit audit;

    private volatile ModerationSettings settings;

    public NoteService(Plugin plugin, NoteRegistry notes, NoteStorage storage, Audit audit,
                       ModerationSettings settings) {
        this.plugin = plugin;
        this.notes = notes;
        this.storage = storage;
        this.audit = audit;
        this.storage.bind(notes);
        settings(settings);
    }

    /** Reads the notes into the registry. Called once, when the module starts. */
    public void load() {
        storage.refresh();
    }

    /**
     * Writes one down.
     *
     * @param author null for the console
     * @return the note, or empty when there was nothing to write
     */
    public Optional<StaffNote> add(UUID subject, String subjectName, UUID author, String authorName,
                                   String text) {
        if (subject == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        StaffNote note = storage.admit(id -> new StaffNote(id, subject, author, authorName, text, now));
        record("note-written", author, authorName, subject, subjectName, note.id(), text);
        return Optional.of(note);
    }

    /**
     * Takes one back out.
     *
     * <p>Audited before it goes, and with what it said: a note that can be removed without trace is one
     * a moderator can quietly delete about themselves, which is the whole reason the trail exists.
     */
    public boolean remove(String id, UUID actor, String actorName) {
        Optional<StaffNote> found = notes.byId(id);
        if (found.isEmpty()) {
            return false;
        }
        StaffNote going = found.get();
        storage.drop(id);
        changed();
        record("note-removed", actor, actorName, going.subject(), null, going.id(), going.text());
        return true;
    }

    /** What the staff have written about somebody, newest first. */
    public List<StaffNote> about(UUID subject) {
        return notes.about(subject);
    }

    /** How many, for the lore line on somebody's button. */
    public int countAbout(UUID subject) {
        return notes.countAbout(subject);
    }

    // ---------------------------------------------------------------------------- writing

    /** Asks for what this server changed to be written, off the server thread. */
    public void changed() {
        Scheduling.async(plugin, storage::flush);
    }

    /**
     * Writes what this server changed, and only that, onto what the database holds now — so a note written
     * on another server in the meantime is never put back.
     */
    public boolean flush() {
        return storage.flush();
    }

    /** For a shutdown, which has no next pass. */
    public boolean flushNow() {
        return storage.flush();
    }

    private void record(String action, UUID actor, String actorName, UUID subject, String subjectName,
                        String id, String text) {
        if (!settings.auditEverything()) {
            return;
        }
        audit.record(AuditEntry.of("moderation", action)
                .by(actor, actorName)
                .to(subject, subjectName)
                .with("note", id)
                .saying(text));
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings == null ? ModerationSettings.DEFAULTS : settings;
    }

    @Override
    public String describe() {
        return "the staff notes: what is remembered about somebody that is not a punishment";
    }
}
