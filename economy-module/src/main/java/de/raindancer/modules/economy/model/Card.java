package de.raindancer.modules.economy.model;

/**
 * One playing card.
 *
 * @param rank 1 for an ace up to 13 for a king
 */
public record Card(int rank, Suit suit) {

    private static final String[] NAMES = {"", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] LONG = {"", "Ace", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Jack", "Queen", "King"};

    public Card {
        if (rank < 1 || rank > 13) {
            throw new IllegalArgumentException("no such rank: " + rank);
        }
    }

    public String label() {
        return NAMES[rank] + suit.symbol();
    }

    public String fullName() {
        return LONG[rank] + " of " + suit.name().charAt(0) + suit.name().substring(1).toLowerCase();
    }
}
