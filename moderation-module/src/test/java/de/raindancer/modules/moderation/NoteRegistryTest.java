package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.StaffNote;
import de.raindancer.modules.moderation.store.NoteRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The quiet half of a player's record.
 *
 * <h2>Why notes are separate from punishments</h2>
 * Because most of what staff need to remember about somebody is not a punishment: "asked about this
 * build twice", "says they share the account with a sibling", "appealed, told to wait a week". Written
 * into a punishment reason it would be a punishment they never got; written nowhere it is in one
 * person's head and gone when they stop playing.
 *
 * <p>A note is never shown to the player it is about. That is the whole reason it can be honest.
 */
class NoteRegistryTest {

    private final UUID bram = UUID.randomUUID();
    private final UUID ayla = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private final Instant when = Instant.parse("2026-08-03T12:00:00Z");

    private final NoteRegistry notes = new NoteRegistry();

    private StaffNote note(String id, UUID about, Instant at) {
        return new StaffNote(id, about, staff, "Cyra", "worth watching", at);
    }

    @Test
    @DisplayName("a note needs an id, a subject and something written on it")
    void whatANoteNeeds() {
        assertThatThrownBy(() -> new StaffNote(" ", bram, staff, "Cyra", "x", when))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaffNote("N1", null, staff, "Cyra", "x", when))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaffNote("N1", bram, staff, "Cyra", "   ", when))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaffNote("N1", bram, staff, "Cyra", "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("notes come back grouped by who they are about, newest first")
    void bySubject() {
        notes.add(note("N1", bram, when.minusSeconds(600)));
        notes.add(note("N2", bram, when));
        notes.add(note("N3", ayla, when));

        assertThat(notes.about(bram)).extracting(StaffNote::id).containsExactly("N2", "N1");
        assertThat(notes.about(ayla)).extracting(StaffNote::id).containsExactly("N3");
        assertThat(notes.about(UUID.randomUUID())).isEmpty();
        assertThat(notes.about(null)).isEmpty();
    }

    @Test
    @DisplayName("one can be taken back out, wherever it was filed")
    void removing() {
        notes.add(note("N1", bram, when));

        assertThat(notes.remove("N1")).isTrue();
        assertThat(notes.remove("N1")).isFalse();
        assertThat(notes.about(bram)).isEmpty();
        assertThat(notes.size()).isZero();
    }

    @Test
    @DisplayName("removing the last note about somebody does not leave an empty entry behind")
    void noEmptyEntries() {
        // The bug this is about: a map keyed by player that never removes its empty lists grows by one
        // entry per player who has ever had a note, for the life of the server.
        notes.add(note("N1", bram, when));
        notes.remove("N1");

        assertThat(notes.subjects()).isEmpty();
    }

    @Test
    @DisplayName("adding the same id twice replaces rather than duplicates")
    void addingReplaces() {
        notes.add(note("N1", bram, when));
        notes.add(new StaffNote("N1", bram, staff, "Cyra", "changed my mind", when));

        assertThat(notes.about(bram)).singleElement()
                .extracting(StaffNote::text).isEqualTo("changed my mind");
    }

    @Test
    @DisplayName("a note that moves to another player leaves the first one")
    void movingSubject() {
        notes.add(note("N1", bram, when));
        notes.add(note("N1", ayla, when));

        assertThat(notes.about(bram)).isEmpty();
        assertThat(notes.about(ayla)).hasSize(1);
        assertThat(notes.size()).isOne();
    }

    @Test
    @DisplayName("the list handed out is a copy")
    void listsAreCopies() {
        notes.add(note("N1", bram, when));

        notes.about(bram).clear();

        assertThat(notes.about(bram)).hasSize(1);
    }

    @Test
    @DisplayName("ids are handed out in order and carry on across a reload")
    void numbering() {
        notes.add(note("N5", bram, when));

        assertThat(notes.nextId()).isEqualTo("N6");
    }

    @Test
    @DisplayName("clearing empties it")
    void clearing() {
        notes.add(note("N1", bram, when));
        notes.clear();

        assertThat(notes.size()).isZero();
        assertThat(notes.subjects()).isEmpty();
    }
}
