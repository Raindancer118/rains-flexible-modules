package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.model.ListingRefusal;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** What the next bid must be, when an auction ends and what it costs. */
public final class AuctionRule implements IEconomyRule {

    /**
     * The auction meant by what staff typed: the short id menus and chat show (or a start of it, when only
     * one fits), else the newest auction of a seller by that name.
     */
    public Optional<Auction> pick(List<Auction> auctions, String typed) {
        String wanted = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<Auction> byId = auctions.stream()
                .filter(auction -> auction.id().toString().toLowerCase(Locale.ROOT).startsWith(wanted)).toList();
        if (byId.size() == 1) {
            return Optional.of(byId.getFirst());
        }
        if (byId.size() > 1) {
            return Optional.empty();
        }
        return auctions.stream().filter(auction -> auction.sellerName().equalsIgnoreCase(wanted))
                .max(Comparator.comparingLong(Auction::listedAt));
    }

    /**
     * The smallest bid accepted next: the starting price first, then the current bid raised by the larger of
     * a fixed step and a percentage — at least one more, never above the buyout.
     */
    public Money nextMinimum(Auction auction, Money step, double stepPercent) {
        if (!auction.hasBid()) {
            return auction.start();
        }
        Money raise = step.max(Money.of((long) Math.ceil(auction.bid().minor() * Math.max(0, stepPercent) / 100.0)))
                .max(Money.of(1));
        Money next = auction.bid().plus(raise);
        return auction.hasBuyout() ? next.min(auction.buyout()) : next;
    }

    /** A bid with less than {@code snipeSeconds} left gives everybody that long again to answer it. */
    public long endAfterBid(long endsAt, long now, int snipeSeconds) {
        long window = Math.max(0, snipeSeconds) * 1000L;
        return endsAt - now < window ? now + window : endsAt;
    }

    public Money capped(Auction auction, Money amount) {
        return auction.hasBuyout() ? amount.min(auction.buyout()) : amount;
    }

    public boolean boughtOut(Auction auction) {
        return auction.hasBuyout() && auction.hasBid() && auction.bid().isAtLeast(auction.buyout());
    }

    public Money fee(Money price, double percent) {
        return Money.of((long) Math.floor(price.minor() * Math.max(0, percent) / 100.0)).min(price);
    }

    /** How long an auction runs: what the seller asked for within the server's limits, or the default. */
    public int seconds(int wanted, int min, int max, int fallback) {
        int asked = wanted <= 0 ? fallback : wanted;
        return Math.max(min, Math.min(max, asked));
    }

    public Optional<ListingRefusal> refusal(Money start, Money buyout, Money smallestStart, int queued, int queueSize,
                                            int mine, int perPlayer) {
        if (!start.isAtLeast(smallestStart) || !start.isPositive()) {
            return Optional.of(ListingRefusal.START_TOO_LOW);
        }
        if (buyout.isPositive() && !buyout.isAtLeast(start)) {
            return Optional.of(ListingRefusal.BUYOUT_BELOW_START);
        }
        if (queued >= queueSize) {
            return Optional.of(ListingRefusal.QUEUE_FULL);
        }
        if (mine >= perPlayer) {
            return Optional.of(ListingRefusal.TOO_MANY);
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "what an auction's next bid must be, when it ends and what it costs";
    }
}
