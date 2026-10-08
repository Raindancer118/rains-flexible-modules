package de.raindancer.modules.economy.model;

import java.util.UUID;

/** An item owed to a player by the auction house — won, unsold or taken off — until they pick it up. */
public record AuctionClaim(UUID id, UUID player, byte[] item, String itemName, Reason reason, long at) {

    public enum Reason { WON, UNSOLD, CANCELLED, RAFFLE }
}
