package de.raindancer.modules.manhunt.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a finished hunt comes down to, for the chat line at the end and the summary page: who caught
 * whom and when, how fast the Runners reached each milestone, and the three players worth naming.
 */
public record HuntSummary(HuntRecord record, List<Catch> catches, List<Split> splits,
                          Optional<PlayerResult> hunterMvp, Optional<PlayerResult> runnerMvp,
                          Optional<PlayerResult> explorer) {

    /** A Runner out of the hunt; {@code byName} is null for one caught by staying away. */
    public record Catch(String runnerName, String byName, long atMillis) {
    }

    public record Split(Milestone milestone, String whoName, long atMillis) {
    }

    public static HuntSummary of(HuntRecord record) {
        List<Catch> catches = new ArrayList<>();
        List<Split> splits = new ArrayList<>();
        for (TimelineEvent event : record.events()) {
            switch (event.kind()) {
                case CAUGHT -> catches.add(new Catch(event.whoName(), event.otherName(), event.atMillis()));
                case CAUGHT_AWAY -> catches.add(new Catch(event.whoName(), null, event.atMillis()));
                case MILESTONE -> Milestone.byId(event.detail())
                        .ifPresent(milestone -> splits.add(new Split(milestone, event.whoName(), event.atMillis())));
                default -> { }
            }
        }
        splits.sort(Comparator.comparing(Split::milestone));
        Optional<PlayerResult> hunterMvp = record.players().stream()
                .filter(result -> !result.runner() && result.catches() > 0)
                .max(Comparator.comparingInt(PlayerResult::catches)
                        .thenComparing(Comparator.comparingInt(PlayerResult::deaths).reversed())
                        .thenComparingDouble(PlayerResult::distance));
        Optional<PlayerResult> runnerMvp = record.players().stream()
                .filter(PlayerResult::runner)
                .max(Comparator.comparingLong(PlayerResult::survivedMillis)
                        .thenComparingDouble(PlayerResult::distance));
        Optional<PlayerResult> explorer = record.players().stream()
                .filter(result -> result.distance() > 0)
                .max(Comparator.comparingDouble(PlayerResult::distance));
        return new HuntSummary(record, List.copyOf(catches), List.copyOf(splits), hunterMvp, runnerMvp, explorer);
    }

    /** {@code 12:03}, or {@code 1:02:03} for a long one. */
    public static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, seconds / 60 % 60, seconds % 60)
                : "%d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
