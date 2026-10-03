package de.raindancer.modules.speedrun.manhunt.stats;

/** One player's numbers over every hunt this server kept. */
public record PlayerStats(String name, double rating, int hunts, int runnerHunts, int runnerWins,
                          int hunterHunts, int hunterWins, int catches, int deaths, int timesCaught,
                          long survivedMillis, long bestSurvivalMillis, double distance, int portals) {

    public static PlayerStats fresh(String name) {
        return new PlayerStats(name, Rating.START, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** These numbers plus one hunt, at the rating it left them with. */
    public PlayerStats plus(PlayerResult result, double newRating) {
        boolean runner = result.runner();
        return new PlayerStats(result.name() == null ? name : result.name(), newRating, hunts + 1,
                runnerHunts + (runner ? 1 : 0), runnerWins + (runner && result.won() ? 1 : 0),
                hunterHunts + (runner ? 0 : 1), hunterWins + (!runner && result.won() ? 1 : 0),
                catches + result.catches(), deaths + result.deaths(), timesCaught + (result.caught() ? 1 : 0),
                survivedMillis + result.survivedMillis(), Math.max(bestSurvivalMillis, result.survivedMillis()),
                distance + result.distance(), portals + result.portals());
    }

    public int wins() {
        return runnerWins + hunterWins;
    }
}
