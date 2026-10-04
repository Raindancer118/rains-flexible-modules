package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunRunRecord;
import de.raindancer.modules.speedrun.SpeedrunTimeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a hunt is remembered by, read off its run in the one history: who caught whom and when, and
 * the three who stood out — the Hunter with the most catches, the Runner who lasted longest, and
 * whoever covered the most ground. The splits are the run's own, shown on its summary page.
 */
public record HuntSummary(SpeedrunRunRecord run, Winner winner, List<Catch> catches,
                          Optional<PlayerResult> hunterMvp, Optional<PlayerResult> runnerMvp,
                          Optional<PlayerResult> explorer) {

    public enum Winner { RUNNERS, HUNTERS, NOBODY }

    /** @param byName {@code null} for a Runner who was away too long */
    /** How a Runner came to be caught. */
    public enum How {
        /** A Hunter's last hit. */
        BY_A_HUNTER,
        /** A death nobody dealt — a fall, lava, the void. */
        DIED,
        /** Offline longer than the grace. */
        STAYED_AWAY
    }

    /** @param byName the Hunter who caught them, for {@link How#BY_A_HUNTER} only */
    public record Catch(String runnerName, String byName, long atMillis, How how) {

        public Catch(String runnerName, String byName, long atMillis) {
            this(runnerName, byName, atMillis, byName == null ? How.DIED : How.BY_A_HUNTER);
        }
    }

    public static HuntSummary of(SpeedrunRunRecord run) {
        List<Catch> catches = new ArrayList<>();
        for (SpeedrunTimeline.Entry entry : run.timeline()) {
            switch (entry.kind()) {
                case CAUGHT -> catches.add(new Catch(run.nameOf(entry.who()),
                        entry.other() == null ? null : run.nameOf(entry.other()), entry.at().toMillis()));
                case CAUGHT_AWAY -> catches.add(new Catch(run.nameOf(entry.who()), null, entry.at().toMillis(),
                        How.STAYED_AWAY));
                default -> { }
            }
        }
        Optional<PlayerResult> hunterMvp = run.players().stream()
                .filter(result -> !result.runner() && result.catches() > 0)
                .max(Comparator.comparingInt(PlayerResult::catches)
                        .thenComparing(Comparator.comparingInt(PlayerResult::deaths).reversed())
                        .thenComparingDouble(PlayerResult::distance));
        Optional<PlayerResult> runnerMvp = run.players().stream()
                .filter(PlayerResult::runner)
                .max(Comparator.comparingLong(PlayerResult::survivedMillis)
                        .thenComparingDouble(PlayerResult::distance));
        Optional<PlayerResult> explorer = run.players().stream()
                .filter(result -> result.distance() > 0)
                .max(Comparator.comparingDouble(PlayerResult::distance));
        Winner winner = SpeedrunHistory.RUNNERS.equals(run.winner()) ? Winner.RUNNERS
                : SpeedrunHistory.HUNTERS.equals(run.winner()) ? Winner.HUNTERS : Winner.NOBODY;
        return new HuntSummary(run, winner, List.copyOf(catches), hunterMvp, runnerMvp, explorer);
    }

    public static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, seconds / 60 % 60, seconds % 60)
                : "%d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
