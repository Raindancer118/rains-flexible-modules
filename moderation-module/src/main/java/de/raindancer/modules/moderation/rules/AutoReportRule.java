package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.Report;

import java.util.List;
import java.util.UUID;

/**
 * Reports a machine files (the anti-cheat's movement hacks, like the x-ray verdict): worded
 * {@code "<kind>: …"}, and never a second while an unfinished one of the same kind about the same
 * player is in the queue — staff read the first, and a cheater who keeps cheating does not need to
 * fill the queue while they do.
 */
public final class AutoReportRule implements IModerationRule {

    public String text(String kind, String what) {
        return kind.trim() + ": " + what.trim();
    }

    /** Whether a report of this kind from a machine, still open or claimed, exists among everything about them. */
    public boolean alreadyWaiting(UUID subject, String kind, List<Report> about) {
        String prefix = kind.trim() + ": ";
        for (Report report : about) {
            if (report != null && report.isLive() && report.reporter() == null && subject.equals(report.subject())
                    && report.text().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return "whether a machine's report is already waiting for staff";
    }
}
