package de.raindancer.modules.performance.model;

import de.raindancer.modules.performance.rules.LagRule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What one look at a slow server found. Findings are numbered from 1 in the order they are listed,
 * and that number is what a fix button or {@code /perf fix} names — so a report never reorders.
 */
public record Report(int number, Instant at, String trigger, LagRule.State state, double meanMs, double p95Ms,
                     double worstMs, double tps, int simulationDistance, List<Attribution.Share> causes,
                     List<Finding> findings, List<Fix> advice) {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    public Report {
        causes = List.copyOf(causes);
        findings = List.copyOf(findings);
        advice = List.copyOf(advice);
    }

    public Optional<Finding> finding(int oneBased) {
        return oneBased >= 1 && oneBased <= findings.size() ? Optional.of(findings.get(oneBased - 1)) : Optional.empty();
    }

    /** The report as plain text: for its file, and for the console. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Report #" + number + " — " + WHEN.format(at) + " — " + trigger);
        lines.add(String.format(Locale.ROOT, "%.1f TPS · a tick takes %.1f ms on average, %.1f ms at the 95th percentile, %.1f ms at worst",
                tps, meanMs, p95Ms, worstMs));
        if (causes.isEmpty()) {
            lines.add("Where the time went: not sampled.");
        } else {
            lines.add("Where the time went:");
            for (Attribution.Share share : causes.subList(0, Math.min(6, causes.size()))) {
                lines.add(String.format(Locale.ROOT, "  %.0f%% %s", share.percent(), share.cause().readable()));
            }
        }
        if (findings.isEmpty()) {
            lines.add("No chunk holds too much of anything.");
        } else {
            lines.add("Found:");
            for (int i = 0; i < findings.size(); i++) {
                Finding finding = findings.get(i);
                ChunkCensus where = finding.where();
                String owner = finding.names().isEmpty() ? "" : " (" + String.join(", ", finding.names()) + ")";
                lines.add(String.format(Locale.ROOT, "  %d. %d %s — %s, x %d z %d — %s%s", i + 1, finding.count(), finding.what(),
                        where.world(), where.blockX(), where.blockZ(), finding.landName(), owner));
                List<String> fixes = finding.fixes().stream().filter(Fix::isAction).map(this::describe).toList();
                if (!fixes.isEmpty()) {
                    lines.add("     could: " + String.join("; ", fixes) + "  (/perf fix " + number + " " + (i + 1) + ")");
                }
            }
        }
        for (Fix fix : advice) {
            lines.add("Advice: " + describe(fix));
        }
        return lines;
    }

    private String describe(Fix fix) {
        return switch (fix.action()) {
            case THIN_ANIMALS -> "thin to " + fix.amount() + " (named, tamed and leashed stay)";
            case CLEAR_ITEMS -> "clear the items lying there";
            case CLEAR_MONSTERS -> "clear the monsters that would despawn anyway";
            case TELL_OWNER -> "tell the owner";
            case SIMULATION_DISTANCE -> "simulation distance " + simulationDistance + " → " + fix.amount()
                    + " (until the next restart; /perf fix " + number + " distance)";
            case SUSPECT_PLUGIN -> "the plugin " + fix.what() + " had " + fix.amount() + "% of the busy time";
        };
    }
}
