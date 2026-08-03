package de.raindancer.modules.moderation.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a player told the staff, and what happened to it.
 *
 * <h2>Nothing is ever overwritten</h2>
 * A moderator's outcome sits <em>beside</em> what was reported, never over it. Otherwise "what did they
 * actually say" has no answer the moment somebody has typed into the same field, which is the state
 * every hand-rolled report system reaches within a month.
 *
 * <h2>Why the names are stored as well as the ids</h2>
 * Because a report is read months later, sometimes about somebody who has since renamed and sometimes
 * about somebody the server has forgotten. The id is what the code matches on and the name is what a
 * person reads; keeping only the id gives a queue of uuids, and keeping only the name gives a queue
 * nothing can act on.
 *
 * @param reporter null when the console or an automated check filed it
 * @param handler  who picked it up, if anybody has
 * @param outcome  what they decided, once it is closed
 * @param closedAt when it was closed; null while it is still live
 */
public record Report(String id, UUID reporter, String reporterName, UUID subject, String subjectName,
                     String text, Instant at, ReportState state, UUID handler, String handlerName,
                     String outcome, Instant closedAt) {

    public Report {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a report needs an id");
        }
        if (subject == null) {
            throw new IllegalArgumentException("a report needs somebody it is about");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a report with nothing written on it is not a report");
        }
        if (at == null) {
            throw new IllegalArgumentException("a report needs to say when it was filed");
        }
        if (state == null) {
            throw new IllegalArgumentException("a report needs a state");
        }
        id = id.trim();
        text = text.trim();
        reporterName = nameOr(reporterName, "the console");
        subjectName = nameOr(subjectName, "somebody");
    }

    private static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.trim();
    }

    /** A new report, waiting for somebody to pick it up. */
    public static Report filed(String id, UUID reporter, String reporterName, UUID subject,
                               String subjectName, String text, Instant at) {
        return new Report(id, reporter, reporterName, subject, subjectName, text, at,
                ReportState.OPEN, null, null, null, null);
    }

    /** Somebody is on it, so nobody else walks to the same grief. */
    public Report claimedBy(UUID who, String name) {
        return new Report(id, reporter, reporterName, subject, subjectName, text, at,
                ReportState.CLAIMED, who, nameOr(name, "somebody"), outcome, null);
    }

    /** Handed back to the queue — the moderator who claimed it had to go. */
    public Report released() {
        return new Report(id, reporter, reporterName, subject, subjectName, text, at,
                ReportState.OPEN, null, null, outcome, null);
    }

    /** Looked at, and something was done about it. */
    public Report resolved(UUID who, String name, String whatWasDone, Instant when) {
        return closed(ReportState.RESOLVED, who, name, whatWasDone, when);
    }

    /** Looked at, and there was nothing in it. */
    public Report rejected(UUID who, String name, String why, Instant when) {
        return closed(ReportState.REJECTED, who, name, why, when);
    }

    private Report closed(ReportState how, UUID who, String name, String said, Instant when) {
        return new Report(id, reporter, reporterName, subject, subjectName, text, at, how, who,
                nameOr(name, "somebody"),
                // Recorded as something rather than as nothing: a closed report whose outcome is blank
                // reads as one that was never actually looked at.
                said == null || said.isBlank() ? "no reason given" : said.trim(),
                when == null ? Instant.now() : when);
    }

    /** Waiting for somebody to pick it up. */
    public boolean isOpen() {
        return state == ReportState.OPEN;
    }

    /** The same thing, named for the queue that shows it. */
    public boolean isWaiting() {
        return isOpen();
    }

    /** Finished with, either way. */
    public boolean isClosed() {
        return state.isClosed();
    }

    /** Still in the queue at all — waiting or being looked at. */
    public boolean isLive() {
        return !state.isClosed();
    }

    /** Who filed it. Empty when the console did. */
    public Optional<UUID> reporterId() {
        return Optional.ofNullable(reporter);
    }

    /** Who is on it, or dealt with it. */
    public Optional<UUID> handlerId() {
        return Optional.ofNullable(handler);
    }

    /** One line, for a console log or a chat row. */
    public String summary() {
        return id + " — " + subjectName + ": " + text + " (" + state.describe() + ")";
    }
}
