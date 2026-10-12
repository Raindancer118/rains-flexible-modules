package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportPreset;
import de.raindancer.modules.moderation.model.ReportSeverity;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * How bad a report looks at first glance: what it is about, raised a step when three or more different
 * people have reported the same player and those reports are still live. One person reporting the same
 * player three times is one opinion, not three.
 */
public final class ReportSeverityRule implements IModerationRule {

    static final int PILE_UP = 3;

    /** @param aboutTheSamePlayer every report about the same player, this one included or not */
    public ReportSeverity judge(Report report, Collection<Report> aboutTheSamePlayer) {
        int level = ReportPreset.of(report).severity();
        long reporters = aboutTheSamePlayer.stream()
                .filter(other -> other.subject().equals(report.subject()) && other.isLive())
                .map(other -> other.reporterId().orElse(new UUID(0, 0)))
                .filter(Objects::nonNull)
                .distinct()
                .count();
        if (reporters >= PILE_UP) {
            level++;
        }
        return ReportSeverity.of(level);
    }

    @Override
    public String describe() {
        return "how bad a report looks at first glance";
    }
}
