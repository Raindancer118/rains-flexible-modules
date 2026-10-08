package de.raindancer.modules.economy.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Several decks shuffled together, dealt from the top — what a casino deals from. Holds its own cards and
 * nothing else; whoever deals decides when it is time for a fresh one.
 */
public final class Shoe {

    private final List<Card> cards = new ArrayList<>();
    private final int decks;

    public Shoe(int decks, Random random) {
        this.decks = Math.max(1, decks);
        for (int deck = 0; deck < this.decks; deck++) {
            for (Suit suit : Suit.values()) {
                for (int rank = 1; rank <= 13; rank++) {
                    cards.add(new Card(rank, suit));
                }
            }
        }
        Collections.shuffle(cards, random);
    }

    public Card draw() {
        return cards.removeLast();
    }

    public int left() {
        return cards.size();
    }

    /** Whether less than a quarter is left — where a dealer puts in a fresh shoe. */
    public boolean nearlyEmpty() {
        return cards.size() < decks * 52 / 4;
    }

    /** How many of each rank are left, index 1 to 13. */
    public int[] ranksLeft() {
        int[] counts = new int[14];
        cards.forEach(card -> counts[card.rank()]++);
        return counts;
    }
}
