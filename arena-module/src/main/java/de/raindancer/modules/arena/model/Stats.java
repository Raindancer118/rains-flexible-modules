package de.raindancer.modules.arena.model;

import java.util.UUID;

/** What somebody has done in the arena, all time. */
public record Stats(UUID player, String name, int wins, int losses, int draws, int kills, int deaths, int bestWave) {

    public static Stats none(UUID player, String name) {
        return new Stats(player, name, 0, 0, 0, 0, 0, 0);
    }

    public Stats plus(int wins, int losses, int draws, int kills, int deaths, int wave) {
        return new Stats(player, name, this.wins + wins, this.losses + losses, this.draws + draws,
                this.kills + kills, this.deaths + deaths, Math.max(bestWave, wave));
    }

    public Stats named(String fresh) {
        return new Stats(player, fresh, wins, losses, draws, kills, deaths, bestWave);
    }
}
