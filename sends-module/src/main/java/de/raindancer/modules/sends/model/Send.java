package de.raindancer.modules.sends.model;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Items on their way from one player to another.
 *
 * <p>Immutable: a change of state is a new {@code Send}, so what was written to disk and what is held in
 * memory can never be two half-updated versions of the same object.
 *
 * <h2>The states</h2>
 * {@code PENDING} waits for the recipient. When they accept it is {@code DELIVERED}; when it is declined,
 * cancelled or runs out it is {@code RETURNING} — the items are the sender's again but still held here until
 * they have room — and {@code RETURNED} once they have them. {@code PENDING} and {@code RETURNING} are the open
 * states: somebody still has a claim on the items, so those are the ones kept in memory and read back at start.
 */
public record Send(UUID id, UUID sender, String senderName, UUID recipient, String recipientName,
                   List<ItemStack> items, long createdAt, long expiresAt, State state, long settledAt) {

    public enum State {
        PENDING, DELIVERED, RETURNING, RETURNED;

        public boolean isOpen() {
            return this == PENDING || this == RETURNING;
        }
    }

    public Send {
        items = List.copyOf(items);
    }

    public static Send create(UUID sender, String senderName, UUID recipient, String recipientName,
                              List<ItemStack> items, long now, long lifetimeMillis) {
        if (sender.equals(recipient)) {
            throw new IllegalArgumentException("A send needs two different players.");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A send of nothing is not a send.");
        }
        return new Send(UUID.randomUUID(), sender, senderName, recipient, recipientName, items, now,
                now + lifetimeMillis, State.PENDING, 0);
    }

    public Send withState(State next, long at) {
        return new Send(id, sender, senderName, recipient, recipientName, items, createdAt, expiresAt, next, at);
    }

    /** Only a send still waiting for its recipient can run out. */
    public boolean isExpiredAt(long now) {
        return state == State.PENDING && now >= expiresAt;
    }

    public long millisLeft(long now) {
        return Math.max(0, expiresAt - now);
    }

    /** How many items there are, not how many stacks. */
    public int totalItems() {
        int total = 0;
        for (ItemStack item : items) {
            total += item.getAmount();
        }
        return total;
    }

    /** What a player types after {@code /send accept}. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}
