package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * How a raffle ended.
 *
 * @param winner null when nobody bought a ticket, or when it was called off
 * @param claim  the prize item, owed to the winner — or back to the host; null for a money prize
 * @param paid   what reached the host, the fee already taken
 */
public record RaffleDraw(Raffle raffle, UUID winner, AuctionClaim claim, Money paid, Money fee) {
}
