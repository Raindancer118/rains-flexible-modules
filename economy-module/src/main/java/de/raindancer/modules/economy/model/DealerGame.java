package de.raindancer.modules.economy.model;

import java.util.Locale;
import java.util.Optional;

/** What a dealer standing in the world deals. */
public enum DealerGame {
    BLACKJACK("Blackjack"), BACCARAT("Baccarat"), HILO("Hi-Lo"), ROULETTE("Roulette"), SLOTS("Slots"),
    MINES("Mines"), CRASH("Crash"), RACE("Horse race"), LOTTERY("Lottery"), CASINO("Casino");

    private final String title;

    DealerGame(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static Optional<DealerGame> read(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String key = typed.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (DealerGame game : values()) {
            if (game.name().equals(key)) {
                return Optional.of(game);
            }
        }
        return Optional.empty();
    }
}
