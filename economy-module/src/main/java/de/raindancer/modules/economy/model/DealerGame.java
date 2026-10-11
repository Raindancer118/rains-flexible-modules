package de.raindancer.modules.economy.model;

import java.util.Locale;
import java.util.Optional;

/** What a dealer standing in the world deals. The casino dealer opens the whole casino screen. */
public enum DealerGame {
    COINFLIP("Coin flip", Game.COINFLIP), DICE("Dice", Game.DICE), BLACKJACK("Blackjack", Game.BLACKJACK),
    BACCARAT("Baccarat", Game.BACCARAT), HILO("Hi-Lo", Game.HILO), ROULETTE("Roulette", Game.ROULETTE),
    SLOTS("Slots", Game.SLOTS), MINES("Mines", Game.MINES), CRASH("Crash", Game.CRASH),
    RACE("Horse race", Game.RACE), SCRATCH("Scratch card", Game.SCRATCH), LOTTERY("Lottery", Game.LOTTERY),
    CASINO("Casino", null);

    private final String title;
    private final Game game;

    DealerGame(String title, Game game) {
        this.title = title;
        this.game = game;
    }

    public String title() {
        return title;
    }

    /** What the player calls whoever deals it: "Coin flip dealer". */
    public String dealer() {
        return title + " dealer";
    }

    /** The game it deals; empty for the casino dealer. */
    public Optional<Game> game() {
        return Optional.ofNullable(game);
    }

    /** Who deals this game. */
    public static DealerGame of(Game game) {
        for (DealerGame dealer : values()) {
            if (dealer.game == game) {
                return dealer;
            }
        }
        throw new IllegalArgumentException("no dealer deals " + game);
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
