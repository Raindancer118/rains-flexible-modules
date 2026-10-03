package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.modules.speedrun.SpeedrunBoard;
import de.raindancer.modules.speedrun.SpeedrunHistory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Manhunt's view of the players' standings — ratings, wins, catches — which live in the one
 * speedrun history, filed under this game. Nothing here is a file of its own any more: what used to
 * be {@code stats.yml} is the history's {@code standings.manhunt}.
 */
public final class StatsStore {

    private final Supplier<SpeedrunHistory> history;
    private final String mode;

    /** @param history the lobby's history — absent until the lobby has one, which reads as nobody known */
    public StatsStore(Supplier<SpeedrunHistory> history, String mode) {
        this.history = history;
        this.mode = mode;
    }

    private Optional<SpeedrunHistory> history() {
        return Optional.ofNullable(history.get());
    }

    public PlayerStats get(UUID id) {
        return history().flatMap(h -> h.standing(mode, id)).orElse(PlayerStats.fresh("somebody"));
    }

    public boolean has(UUID id) {
        return history().flatMap(h -> h.standing(mode, id)).isPresent();
    }

    public double rating(UUID id) {
        return history().map(h -> h.rating(mode, id)).orElse(Rating.START);
    }

    public Optional<UUID> byName(String name) {
        return history().flatMap(h -> h.byName(mode, name));
    }

    public List<PlayerStats> top(SpeedrunBoard board, int limit) {
        return history().map(h -> h.top(mode, board, limit)).orElse(List.of());
    }

    public List<UUID> ranked(SpeedrunBoard board) {
        return history().map(h -> h.ranked(mode, board)).orElse(List.of());
    }
}
