package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.moderation.model.Report;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Whether this report should be taken.
 *
 * <h2>Why a report needs a rule at all</h2>
 * Because the report queue is the one part of a moderation system a player can write to, and a queue
 * anybody can fill is a queue nobody reads. Every refusal here was learnt from a real server: the
 * one-word report, the same grief filed four times in a minute, and the player who worked out that
 * reporting somebody puts their name in front of the staff.
 *
 * <p>All of the limits can be switched off — a small server where everybody knows everybody wants none
 * of them, and a rule an owner has to disable a feature to escape is a rule that gets the feature
 * disabled.
 */
public final class ReportRule implements IModerationRule {

    /**
     * How much somebody has to write before it is worth a moderator walking over.
     *
     * <p>Eight characters: enough to exclude {@code hes bad} and {@code grief}, short enough not to
     * turn a genuine report into an essay competition.
     */
    public static final int SHORTEST = 8;

    /** Refusal keys, which are message keys. */
    public static final String TOO_SHORT = "moderation.report.too-short";
    public static final String NOT_YOURSELF = "moderation.report.not-yourself";
    public static final String NOBODY_THERE = "moderation.report.nobody-there";
    public static final String TOO_SOON = "moderation.report.too-soon";
    public static final String ALREADY_OPEN = "moderation.report.already-open";
    public static final String TOO_MANY = "moderation.report.too-many";

    private final Duration cooldown;
    private final int mostOpenPerReporter;
    private final int shortest;

    /** With the built-in minimum length. */
    public ReportRule(Duration cooldown, int mostOpenPerReporter) {
        this(cooldown, mostOpenPerReporter, SHORTEST);
    }

    /**
     * @param cooldown            zero or negative for no wait between reports
     * @param mostOpenPerReporter zero or fewer for no limit
     * @param shortest            zero or fewer for no minimum length
     */
    public ReportRule(Duration cooldown, int mostOpenPerReporter, int shortest) {
        this.cooldown = cooldown == null ? Duration.ZERO : cooldown;
        this.mostOpenPerReporter = mostOpenPerReporter;
        this.shortest = shortest;
    }

    /**
     * Whether this report may be filed.
     *
     * @param theirs everything this reporter has already filed, in any state; null for nothing
     * @param now    the moment the report arrived
     */
    public Verdict mayFile(UUID reporter, UUID subject, String text, List<Report> theirs, Instant now) {
        String written = text == null ? "" : text.trim();
        if (shortest > 0 && written.length() < shortest) {
            return Verdict.refused(TOO_SHORT, shortest);
        }
        if (subject == null) {
            return Verdict.refused(NOBODY_THERE);
        }
        if (subject.equals(reporter)) {
            return Verdict.refused(NOT_YOURSELF);
        }
        if (reporter == null) {
            // The console, or an automated check. Neither is worth rate limiting, and both are how a
            // server would feed its own detection into the same queue.
            return Verdict.allowed();
        }

        List<Report> already = theirs == null ? List.of() : theirs;
        if (isTooSoon(already, now)) {
            return Verdict.refused(TOO_SOON, describeWait(already, now));
        }
        for (Report report : already) {
            if (report != null && report.isLive() && subject.equals(report.subject())) {
                // Two entries about one grief is two moderators walking to the same place. The
                // cooldown alone does not catch it: the second one usually arrives an hour later with
                // more detail, which is well past any wait worth imposing.
                return Verdict.refused(ALREADY_OPEN, report.id());
            }
        }
        if (mostOpenPerReporter > 0 && countLive(already) >= mostOpenPerReporter) {
            return Verdict.refused(TOO_MANY, mostOpenPerReporter);
        }
        return Verdict.allowed();
    }

    private boolean isTooSoon(List<Report> theirs, Instant now) {
        if (cooldown.isZero() || cooldown.isNegative() || now == null) {
            return false;
        }
        Instant lastOne = mostRecent(theirs);
        return lastOne != null && lastOne.plus(cooldown).isAfter(now);
    }

    private String describeWait(List<Report> theirs, Instant now) {
        Instant lastOne = mostRecent(theirs);
        if (lastOne == null || now == null) {
            return de.raindancer.core.moderation.punishment.Durations.describe(cooldown);
        }
        Duration left = Duration.between(now, lastOne.plus(cooldown));
        return de.raindancer.core.moderation.punishment.Durations.describe(
                left.isNegative() ? Duration.ZERO : left);
    }

    /** When they last filed one — every state counts, or the cooldown is escaped by being dealt with. */
    private static Instant mostRecent(List<Report> theirs) {
        Instant latest = null;
        for (Report report : theirs) {
            if (report != null && (latest == null || report.at().isAfter(latest))) {
                latest = report.at();
            }
        }
        return latest;
    }

    /** How many of theirs are still in the queue — waiting or being looked at. */
    private static int countLive(List<Report> theirs) {
        int live = 0;
        for (Report report : theirs) {
            if (report != null && report.isLive()) {
                live++;
            }
        }
        return live;
    }

    @Override
    public String describe() {
        return "whether a player may file this report: length, self, wait, duplicate, how many at once";
    }
}
