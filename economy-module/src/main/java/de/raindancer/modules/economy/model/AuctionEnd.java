package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

/**
 * How an auction ended.
 *
 * @param claim the item, owed to the winner when sold and to the seller otherwise
 * @param paid  what reached the seller, the fee already taken
 */
public record AuctionEnd(Auction auction, AuctionClaim claim, boolean sold, Money paid, Money fee) {
}
