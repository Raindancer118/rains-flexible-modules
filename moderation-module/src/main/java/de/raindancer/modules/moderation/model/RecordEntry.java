package de.raindancer.modules.moderation.model;

import de.raindancer.core.moderation.punishment.Punishment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * One line of somebody's record: a punishment they were given, or a report filed about them.
 *
 * <h2>Why reports belong here</h2>
 * Because "the entire record" that leaves half of it out is worse than no record — it reads as complete.
 * A moderator deciding what to do about somebody is asking one question, and the answer was split
 * across two screens with no indication from either that the other existed. Somebody reported four
 * times and never punished looked spotless.
 *
 * <p>They stay distinguishable rather than being flattened into one kind of thing: a report is an
 * accusation and a punishment is a decision, and a record that blurs them would let four reports from
 * the same angry player read like four findings of guilt.
 */
public sealed interface RecordEntry {

    /** When it happened. The one thing both kinds have, and the only thing the merge needs. */
    Instant at();

    /** Something a moderator decided. */
    record Punished(Punishment punishment) implements RecordEntry {

        @Override
        public Instant at() {
            return punishment.givenAt();
        }
    }

    /** Something somebody alleged. Not a finding, and drawn so it cannot be read as one. */
    record Reported(Report report) implements RecordEntry {

        @Override
        public Instant at() {
            return report.at();
        }
    }

    /**
     * Both kinds in one list, newest first.
     *
     * <p>Newest first because the useful end of a long record is the recent end — a moderator opening
     * it is nearly always asking "what happened lately".
     *
     * @param punishments what they were given; null is treated as none
     * @param reports     what was filed about them; null is treated as none
     */
    static List<RecordEntry> merge(Collection<Punishment> punishments, Collection<Report> reports) {
        List<RecordEntry> everything = new ArrayList<>();
        if (punishments != null) {
            for (Punishment punishment : punishments) {
                if (punishment != null) {
                    everything.add(new Punished(punishment));
                }
            }
        }
        if (reports != null) {
            for (Report report : reports) {
                if (report != null) {
                    everything.add(new Reported(report));
                }
            }
        }
        // nullsLast: an entry with no timestamp sorts to the bottom rather than throwing. A record
        // that refuses to open because one row is malformed tells the moderator nothing at all.
        everything.sort(Comparator.comparing(RecordEntry::at,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return everything;
    }
}
