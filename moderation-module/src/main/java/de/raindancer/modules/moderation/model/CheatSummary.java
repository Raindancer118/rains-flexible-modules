package de.raindancer.modules.moderation.model;

import de.raindancer.core.moderation.players.CheatRecords;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the anti-cheat tells a player's page about them. Online, the live levels; offline, levels fade with
 * the player gone, so the last week of evidence stands in — each check at the highest level it reached.
 */
public final class CheatSummary {

    static final long WEEK = 7L * 86_400_000;

    private CheatSummary() {
    }

    /** @param newestFirst their evidence, newest first */
    public static Optional<CheatRecords.Summary> of(PlayerTrack track, List<Evidence> newestFirst, long now) {
        Map<CheckType, Double> levels = new EnumMap<>(CheckType.class);
        if (track != null) {
            levels.putAll(track.violations().snapshot());
        } else {
            for (Evidence entry : newestFirst) {
                if (now - entry.atMillis() <= WEEK) {
                    CheckType.find(entry.check()).ifPresent(check -> levels.merge(check, entry.level(), Math::max));
                }
            }
        }
        levels.values().removeIf(level -> level <= 0);
        if (levels.isEmpty() && newestFirst.isEmpty()) {
            return Optional.empty();
        }
        double total = levels.values().stream().mapToDouble(Double::doubleValue).sum();
        String worst = levels.entrySet().stream().max(Map.Entry.comparingByValue()).map(entry -> entry.getKey().title())
                .orElse(null);
        Evidence last = newestFirst.isEmpty() ? null : newestFirst.getFirst();
        String lastCheck = last == null ? null : CheckType.find(last.check()).map(CheckType::title).orElse(last.check());
        return Optional.of(new CheatRecords.Summary(total, worst, levels.size(), lastCheck,
                last == null ? 0 : last.atMillis(), 0));
    }
}
