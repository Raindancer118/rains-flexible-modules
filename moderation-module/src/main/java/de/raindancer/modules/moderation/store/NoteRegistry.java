package de.raindancer.modules.moderation.store;

import de.raindancer.modules.moderation.model.StaffNote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The staff notes, while the server is up.
 *
 * <h2>Why it is keyed by note and not by player</h2>
 * The obvious shape is a map from player to a list of their notes, and it is the one that grows for
 * ever: removing the last note about somebody leaves an empty list behind, so the map gains an entry
 * per player who has ever had a note and never loses one. That is the same defect this repository's
 * listener interface exists to prevent, one layer down. Keyed by note, {@link #subjects()} is derived
 * and there is nothing to leak.
 */
public final class NoteRegistry {

    /** What a note's id starts with, so one is recognisable in a console line. */
    public static final String PREFIX = "N";

    private final ConcurrentHashMap<String, StaffNote> byId = new ConcurrentHashMap<>();
    private final AtomicLong highest = new AtomicLong();

    /** Puts one in, replacing any with the same id — including one about somebody else. */
    public void add(StaffNote note) {
        if (note == null) {
            return;
        }
        byId.put(key(note.id()), note);
        rememberNumber(note.id());
    }

    /** @return whether there was one to take out */
    public boolean remove(String id) {
        return id != null && byId.remove(key(id)) != null;
    }

    public Optional<StaffNote> byId(String id) {
        return id == null || id.isBlank() ? Optional.empty()
                : Optional.ofNullable(byId.get(key(id)));
    }

    /** What the staff have written about somebody, newest first. */
    public List<StaffNote> about(UUID subject) {
        if (subject == null) {
            return new ArrayList<>();
        }
        List<StaffNote> theirs = new ArrayList<>();
        for (StaffNote note : byId.values()) {
            if (subject.equals(note.subject())) {
                theirs.add(note);
            }
        }
        theirs.sort(Comparator.comparing(StaffNote::at).reversed().thenComparing(StaffNote::id));
        return theirs;
    }

    /** How many notes somebody has, for the lore line on their button. */
    public int countAbout(UUID subject) {
        if (subject == null) {
            return 0;
        }
        int found = 0;
        for (StaffNote note : byId.values()) {
            if (subject.equals(note.subject())) {
                found++;
            }
        }
        return found;
    }

    /** Everybody anything has been written about. Derived, so it cannot grow stale. */
    public Set<UUID> subjects() {
        Set<UUID> everybody = new LinkedHashSet<>();
        for (StaffNote note : byId.values()) {
            everybody.add(note.subject());
        }
        return everybody;
    }

    /** Everything, in no particular order — for the auto-save, which does not care. */
    public Set<StaffNote> snapshot() {
        return new LinkedHashSet<>(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /**
     * Reserves the next id. See {@code ReportRegistry.nextId} for why it reserves rather than
     * predicts — the same race lost two thirds of a batch of notes written at once.
     */
    public String nextId() {
        return PREFIX + highest.incrementAndGet();
    }

    public void clear() {
        byId.clear();
        highest.set(0);
    }

    private static String key(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    /** Moves the high-water mark past this id, when it is one of ours. See {@code ReportRegistry}. */
    private void rememberNumber(String id) {
        String digits = id.trim();
        if (!digits.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return;
        }
        try {
            highest.accumulateAndGet(Long.parseLong(digits.substring(PREFIX.length())), Math::max);
        } catch (NumberFormatException notOneOfOurs) {
            // Left alone on purpose: an imported note keeps its id and simply does not move the count.
        }
    }
}
