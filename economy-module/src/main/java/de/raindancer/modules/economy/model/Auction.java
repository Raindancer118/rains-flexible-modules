package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * One item up for auction, queued or live. The item is held as its serialized bytes, so the ledger can keep
 * it without the server; {@code itemName} is what chat and menus call it.
 *
 * @param buyout {@link Money#ZERO} for none
 * @param bid    the highest bid so far; zero, with no bidder, before the first
 * @param endsAt zero while queued
 */
public record Auction(UUID id, UUID seller, String sellerName, byte[] item, String itemName, Money start,
                      Money buyout, Money bid, UUID bidder, String bidderName, int bids, int seconds,
                      long listedAt, long endsAt) {

    public static Auction listed(UUID id, UUID seller, String sellerName, byte[] item, String itemName, Money start,
                                 Money buyout, int seconds, long listedAt) {
        return new Auction(id, seller, sellerName, item, itemName, start, buyout, Money.ZERO, null, "", 0, seconds,
                listedAt, 0);
    }

    public boolean live() {
        return endsAt > 0;
    }

    public boolean hasBid() {
        return bidder != null;
    }

    public boolean hasBuyout() {
        return buyout.isPositive();
    }

    public Auction started(long now) {
        return new Auction(id, seller, sellerName, item, itemName, start, buyout, bid, bidder, bidderName, bids,
                seconds, listedAt, now + seconds * 1000L);
    }

    public Auction withBid(UUID by, String name, Money amount, long newEnd) {
        return new Auction(id, seller, sellerName, item, itemName, start, buyout, amount, by, name, bids + 1,
                seconds, listedAt, newEnd);
    }

    /** Queued as if listed at {@code at} — the queue is ordered by when auctions were listed. */
    public Auction listedAt(long at) {
        return new Auction(id, seller, sellerName, item, itemName, start, buyout, bid, bidder, bidderName, bids,
                seconds, at, endsAt);
    }

    public Auction endingAt(long at) {
        return new Auction(id, seller, sellerName, item, itemName, start, buyout, bid, bidder, bidderName, bids,
                seconds, listedAt, at);
    }

    public long millisLeft(long now) {
        return live() ? Math.max(0, endsAt - now) : seconds * 1000L;
    }
}
