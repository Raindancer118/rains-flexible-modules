package de.raindancer.modules.moderation.store;

import de.raindancer.modules.moderation.model.Report;

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
 * The reports, while the server is up.
 *
 * <p>The index, never the file — {@code ReportStorage} owns that. Which is what lets every question
 * below be asked in a test without a disk, and what stops a screen quietly writing a file the auto-save
 * does not know about.
 *
 * <h2>Thread safety</h2>
 * Reports arrive from a chat event, which Paper fires off the server thread; the screen that lists them
 * renders on the server thread. So the map is concurrent and every list handed out is a copy — a
 * caller iterating one while a report arrives is the ordinary case here, not an edge one.
 */
public final class ReportRegistry {

    /** What a report's id starts with, so one is recognisable in a console line. */
    public static final String PREFIX = "R";

    private final ConcurrentHashMap<String, Report> byId = new ConcurrentHashMap<>();

    /**
     * The highest number handed out so far.
     *
     * <p>Kept rather than worked out on demand so that {@link #nextId()} stays cheap, and seeded from
     * whatever is loaded — otherwise the first report after a restart is called R1 again and "R1" in
     * yesterday's console log means two different things.
     */
    private final AtomicLong highest = new AtomicLong();

    /** Puts one in, replacing any with the same id. */
    public void add(Report report) {
        if (report == null) {
            return;
        }
        byId.put(key(report.id()), report);
        rememberNumber(report.id());
    }

    /** @return whether there was one to take out */
    public boolean remove(String id) {
        return id != null && byId.remove(key(id)) != null;
    }

    /** One report, however its id was typed. */
    public Optional<Report> byId(String id) {
        return id == null || id.isBlank() ? Optional.empty()
                : Optional.ofNullable(byId.get(key(id)));
    }

    /** Everything, newest first — the order a queue is read in. */
    public List<Report> all() {
        List<Report> everything = new ArrayList<>(byId.values());
        everything.sort(Comparator.comparing(Report::at).reversed()
                .thenComparing(Report::id));
        return everything;
    }

    /** The ones nobody has picked up. */
    public List<Report> waiting() {
        return all().stream().filter(Report::isWaiting).toList();
    }

    /** The ones still in the queue at all — waiting or being looked at. */
    public List<Report> live() {
        return all().stream().filter(Report::isLive).toList();
    }

    /** What somebody has been reported for. */
    public List<Report> about(UUID subject) {
        return subject == null ? List.of()
                : all().stream().filter(report -> subject.equals(report.subject())).toList();
    }

    /** What somebody has reported. */
    public List<Report> by(UUID reporter) {
        return reporter == null ? List.of()
                : all().stream().filter(report -> reporter.equals(report.reporter())).toList();
    }

    /** How many are waiting, for the line somebody coming on shift is shown. */
    public int waitingCount() {
        int found = 0;
        for (Report report : byId.values()) {
            if (report.isWaiting()) {
                found++;
            }
        }
        return found;
    }

    public int size() {
        return byId.size();
    }

    /** Everything, in no particular order — for the auto-save, which does not care. */
    public Set<Report> snapshot() {
        return new LinkedHashSet<>(byId.values());
    }

    /**
     * Reserves the next id.
     *
     * <p><b>Reserves</b>, rather than predicting. The version that answered {@code highest + 1}
     * without moving the counter lost reports: a report arrives from a chat event, so two players
     * filing at once are genuinely on two threads, both were told {@code R1}, and the second
     * {@code add} overwrote the first. No error and no log line — one player's report simply never
     * existed. A race test filed 800 and kept 435 of them.
     *
     * <p>The cost is that an id asked for and not used is skipped, so the numbering has gaps. That is
     * the right way round: a gap is a curiosity, a collision is lost evidence.
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

    /**
     * Moves the high-water mark past this id, when it is one of ours.
     *
     * <p>An id from somewhere else — an import, somebody editing the file — is kept as it is and
     * simply does not move the counter. Refusing it would lose the report; letting it throw would lose
     * the load.
     */
    private void rememberNumber(String id) {
        String digits = id.trim();
        if (!digits.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return;
        }
        try {
            long number = Long.parseLong(digits.substring(PREFIX.length()));
            highest.accumulateAndGet(number, Math::max);
        } catch (NumberFormatException notOneOfOurs) {
            // Left alone on purpose. See above.
        }
    }
}
