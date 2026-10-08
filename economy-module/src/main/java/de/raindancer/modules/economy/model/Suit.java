package de.raindancer.modules.economy.model;

/** The four suits, with how they are drawn. */
public enum Suit {
    SPADES("♠", false), HEARTS("♥", true), DIAMONDS("♦", true), CLUBS("♣", false);

    private final String symbol;
    private final boolean red;

    Suit(String symbol, boolean red) {
        this.symbol = symbol;
        this.red = red;
    }

    public String symbol() {
        return symbol;
    }

    public boolean red() {
        return red;
    }
}
