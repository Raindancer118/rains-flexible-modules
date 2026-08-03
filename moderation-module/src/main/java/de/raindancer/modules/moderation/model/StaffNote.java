package de.raindancer.modules.moderation.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Something the staff want to remember about somebody.
 *
 * <h2>Why notes are not punishments</h2>
 * Most of what staff need to remember is not one: "asked about this build twice", "says they share the
 * account with a sibling", "appealed, told to wait a week". Written into a punishment's reason it would
 * be a punishment they never received, and it would count towards their next one. Written nowhere it is
 * in one person's head, and gone when that person stops playing — which is the actual state of most
 * servers.
 *
 * <p><b>A note is never shown to the player it is about.</b> That is the whole reason it can be honest,
 * and it is why the screens that draw one are all behind {@code ModerationPermission.NOTES}.
 *
 * @param author null when the console wrote it
 */
public record StaffNote(String id, UUID subject, UUID author, String authorName, String text,
                        Instant at) {

    public StaffNote {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a note needs an id");
        }
        if (subject == null) {
            throw new IllegalArgumentException("a note needs somebody it is about");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a note with nothing written on it is not a note");
        }
        if (at == null) {
            throw new IllegalArgumentException("a note needs to say when it was written");
        }
        id = id.trim();
        text = text.trim();
        authorName = authorName == null || authorName.isBlank() ? "the console" : authorName.trim();
    }

    /** Who wrote it. Empty when the console did. */
    public Optional<UUID> authorId() {
        return Optional.ofNullable(author);
    }
}
