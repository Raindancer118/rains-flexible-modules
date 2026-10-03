package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.modules.speedrun.SpeedrunBoard;

/** How a leaderboard shows each kind of number. Plain text: it goes into a placeholder. */
public final class StatsFormat {

    private StatsFormat() {
    }

    public static String value(SpeedrunBoard board, PlayerStats stats) {
        return switch (board) {
            case RATING -> String.valueOf(Math.round(stats.rating()));
            case WINS -> String.valueOf(stats.wins());
            case CATCHES -> String.valueOf(stats.catches());
            case SURVIVAL -> HuntSummary.clock(stats.bestSurvivalMillis());
            case DISTANCE -> Math.round(stats.distance()) + " blocks";
        };
    }
}
