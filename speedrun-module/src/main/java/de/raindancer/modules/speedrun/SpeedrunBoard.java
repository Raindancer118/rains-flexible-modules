package de.raindancer.modules.speedrun;

import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;

import java.util.Locale;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/** What a players' leaderboard is sorted by, for a game with sides and ratings. */
public enum SpeedrunBoard {
    RATING("Rating", PlayerStats::rating),
    WINS("Wins", stats -> stats.wins()),
    CATCHES("Catches", stats -> stats.catches()),
    SURVIVAL("Longest survival", stats -> stats.bestSurvivalMillis()),
    DISTANCE("Distance", PlayerStats::distance);

    private final String label;
    private final ToDoubleFunction<PlayerStats> score;

    SpeedrunBoard(String label, ToDoubleFunction<PlayerStats> score) {
        this.label = label;
        this.score = score;
    }

    public double scoreOf(PlayerStats stats) {
        return score.applyAsDouble(stats);
    }

    public String label() {
        return label;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public SpeedrunBoard next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static Optional<SpeedrunBoard> byId(String id) {
        for (SpeedrunBoard board : values()) {
            if (board.id().equalsIgnoreCase(id)) {
                return Optional.of(board);
            }
        }
        return Optional.empty();
    }
}
