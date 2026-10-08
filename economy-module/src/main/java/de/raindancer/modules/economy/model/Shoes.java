package de.raindancer.modules.economy.model;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/** Every player's own shoe. */
public final class Shoes {

    private final Map<UUID, Shoe> shoes = new ConcurrentHashMap<>();
    private final IntSupplier decks;
    private final Random random;

    public Shoes(IntSupplier decks, Random random) {
        this.decks = decks;
        this.random = random;
    }

    public Card draw(UUID player) {
        return current(player).draw();
    }

    public int[] ranksLeft(UUID player) {
        return current(player).ranksLeft();
    }

    public void forget(UUID player) {
        shoes.remove(player);
    }

    // Odds and draws both go through here: hi-lo pays from the odds, so a shoe swapped between the two would
    // pay a near-empty shoe's long odds on a card dealt from a full one.
    private Shoe current(UUID player) {
        return shoes.compute(player, (id, old) -> old == null || old.nearlyEmpty()
                ? new Shoe(decks.getAsInt(), random) : old);
    }
}
