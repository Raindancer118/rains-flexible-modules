package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * What a bid came to.
 *
 * @param auction the auction after it; as it was when the bid was refused
 * @param outbid  who was the highest bidder before and has been paid back; null if nobody
 * @param payment the money side, for why a {@link Kind#REFUSED} bid could not be paid
 */
public record AuctionBid(Kind kind, Auction auction, UUID outbid, Money refunded, EconomyResult payment) {

    public enum Kind { PLACED, GONE, OWN, TOP, TOO_LOW, REFUSED }

    public boolean placed() {
        return kind == Kind.PLACED;
    }
}
