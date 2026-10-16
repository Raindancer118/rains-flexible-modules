package de.raindancer.modules.ctf.model;

import java.util.UUID;

/** A player's record over every round they played. */
public record Stats(UUID player, String name, int rounds, int wins, int losses, int captures, int returns, int kills,
                    int deaths, int carrierKills, int mvps, long fastestCapture, int xp) {

    public static Stats none(UUID player, String name) {
        return new Stats(player, name, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
