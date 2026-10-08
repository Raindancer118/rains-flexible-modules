package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.model.ListingRefusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("an auction's bids, its clock and its fees")
class AuctionRuleTest {

    private final AuctionRule rule = new AuctionRule();
    private final UUID seller = UUID.randomUUID();

    private Auction auction(long start, long buyout) {
        return Auction.listed(UUID.randomUUID(), seller, "Seller", new byte[]{1}, "Diamond Sword",
                Money.of(start), Money.of(buyout), 120, 1_000L);
    }

    @Test
    @DisplayName("the first bid is the starting price; each one after must beat it by the larger step")
    void nextMinimum() {
        Auction fresh = auction(100, 0);
        assertThat(rule.nextMinimum(fresh, Money.of(10), 5)).isEqualTo(Money.of(100));
        Auction bid = fresh.started(10_000).withBid(UUID.randomUUID(), "Bo", Money.of(100), 10_000);
        assertThat(rule.nextMinimum(bid, Money.of(10), 5)).as("10 beats 5% of 100").isEqualTo(Money.of(110));
        Auction big = fresh.started(10_000).withBid(UUID.randomUUID(), "Bo", Money.of(1_000), 10_000);
        assertThat(rule.nextMinimum(big, Money.of(10), 5)).as("5% of 1,000 beats 10").isEqualTo(Money.of(1_050));
        assertThat(rule.nextMinimum(bid, Money.ZERO, 0)).as("always at least one more").isEqualTo(Money.of(101));
        Auction capped = auction(100, 105).started(10_000).withBid(UUID.randomUUID(), "Bo", Money.of(100), 10_000);
        assertThat(rule.nextMinimum(capped, Money.of(10), 5)).as("never above the buyout").isEqualTo(Money.of(105));
    }

    @Test
    @DisplayName("a bid in the last seconds pushes the end back, so nobody wins by sniping")
    void antiSnipe() {
        assertThat(rule.endAfterBid(100_000, 95_000, 15)).isEqualTo(110_000);
        assertThat(rule.endAfterBid(100_000, 50_000, 15)).as("plenty of time left").isEqualTo(100_000);
        assertThat(rule.endAfterBid(100_000, 95_000, 0)).as("switched off").isEqualTo(100_000);
    }

    @Test
    @DisplayName("a bid at or above the buyout is the buyout, and ends the auction")
    void buyout() {
        Auction listed = auction(100, 500);
        assertThat(rule.capped(listed, Money.of(900))).isEqualTo(Money.of(500));
        assertThat(rule.capped(listed, Money.of(300))).isEqualTo(Money.of(300));
        assertThat(rule.capped(auction(100, 0), Money.of(900))).as("no buyout").isEqualTo(Money.of(900));
        Auction live = listed.started(10_000);
        assertThat(rule.boughtOut(live.withBid(UUID.randomUUID(), "Bo", Money.of(500), 10_000))).isTrue();
        assertThat(rule.boughtOut(live.withBid(UUID.randomUUID(), "Bo", Money.of(499), 10_000))).isFalse();
        assertThat(rule.boughtOut(auction(100, 0).started(1).withBid(UUID.randomUUID(), "Bo", Money.of(9_999), 1)))
                .isFalse();
    }

    @Test
    @DisplayName("the fee is a share of the price, rounded down, never more than the price")
    void fee() {
        assertThat(rule.fee(Money.of(1_000), 5)).isEqualTo(Money.of(50));
        assertThat(rule.fee(Money.of(19), 5)).isEqualTo(Money.ZERO);
        assertThat(rule.fee(Money.of(100), 0)).isEqualTo(Money.ZERO);
        assertThat(rule.fee(Money.of(100), 500)).isEqualTo(Money.of(100));
    }

    @Test
    @DisplayName("the seller picks the length within what the server allows")
    void seconds() {
        assertThat(rule.seconds(0, 60, 600, 120)).as("none asked for: the default").isEqualTo(120);
        assertThat(rule.seconds(10, 60, 600, 120)).isEqualTo(60);
        assertThat(rule.seconds(9_000, 60, 600, 120)).isEqualTo(600);
        assertThat(rule.seconds(300, 60, 600, 120)).isEqualTo(300);
    }

    @Test
    @DisplayName("a listing is refused for a too-low start, a buyout under the start, a full queue or too many")
    void listing() {
        Money min = Money.of(10);
        assertThat(rule.refusal(Money.of(5), Money.ZERO, min, 0, 10, 0, 2)).contains(ListingRefusal.START_TOO_LOW);
        assertThat(rule.refusal(Money.of(50), Money.of(40), min, 0, 10, 0, 2))
                .contains(ListingRefusal.BUYOUT_BELOW_START);
        assertThat(rule.refusal(Money.of(50), Money.ZERO, min, 10, 10, 0, 2)).contains(ListingRefusal.QUEUE_FULL);
        assertThat(rule.refusal(Money.of(50), Money.ZERO, min, 3, 10, 2, 2)).contains(ListingRefusal.TOO_MANY);
        assertThat(rule.refusal(Money.of(50), Money.of(50), min, 3, 10, 1, 2)).isEmpty();
    }
}
