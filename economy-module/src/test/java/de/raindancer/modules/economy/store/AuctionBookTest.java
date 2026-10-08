package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.model.AuctionBid;
import de.raindancer.modules.economy.model.AuctionClaim;
import de.raindancer.modules.economy.model.AuctionEnd;
import de.raindancer.modules.economy.rules.AuctionRule;
import de.raindancer.modules.economy.rules.BalanceRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auctions in the ledger: the item and the bids are held in escrow, an outbid bidder is paid back at once,
 * and all of it — queue, live auction, money held and items owed — survives a restart.
 */
@DisplayName("auctions in the ledger")
class AuctionBookTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Money most = Money.of(1_000_000_00L);
    private final AuctionRule rule = new AuctionRule();
    private final Function<Auction, Money> minimum = auction -> rule.nextMinimum(auction, Money.of(10), 5);
    private Database database;
    private AccountBook book;
    private final UUID seller = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final byte[] sword = {7, 7, 7};

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        book.open(seller, "Sel", Money.of(1_000));
        book.open(ada, "Ada", Money.of(1_000));
        book.open(bo, "Bo", Money.of(1_000));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private AccountBook reopened() {
        book.flush();
        database.close();
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        AccountBook fresh = new AccountBook(database, new BalanceRule(), clock::get);
        fresh.load();
        return fresh;
    }

    private Auction listed(long start, long buyout) {
        Auction auction = Auction.listed(UUID.randomUUID(), seller, "Sel", sword, "Diamond Sword", Money.of(start),
                Money.of(buyout), 120, clock.get());
        assertThat(book.listAuction(auction, Money.ZERO, most, 100, 100).succeeded()).isTrue();
        return auction;
    }

    private AuctionBid bid(Auction auction, UUID who, String name, long amount) {
        return book.bid(auction.id(), who, name, Money.of(amount), minimum, end -> end, clock.get(), most);
    }

    private Money escrow() {
        return book.balance(AccountBook.AUCTION_ESCROW);
    }

    @Test
    @DisplayName("listing takes the fee; listings queue and start one at a time, oldest first")
    void queue() {
        Auction first = Auction.listed(UUID.randomUUID(), seller, "Sel", sword, "Sword", Money.of(100), Money.ZERO,
                120, 1);
        assertThat(book.listAuction(first, Money.of(25), most, 100, 100).succeeded()).isTrue();
        assertThat(book.balance(seller)).as("the listing fee is gone").isEqualTo(Money.of(975));
        Auction second = Auction.listed(UUID.randomUUID(), seller, "Sel", sword, "Sword", Money.of(100), Money.ZERO,
                60, 2);
        book.listAuction(second, Money.ZERO, most, 100, 100);

        assertThat(book.liveAuction()).isEmpty();
        assertThat(bid(first, ada, "Ada", 100).kind()).as("not started yet").isEqualTo(AuctionBid.Kind.GONE);
        assertThat(book.startNextAuction(5_000)).map(Auction::id).contains(first.id());
        assertThat(book.liveAuction()).map(Auction::endsAt).contains(5_000 + 120_000L);
        assertThat(book.startNextAuction(6_000)).as("one at a time").isEmpty();
        assertThat(book.auctions()).extracting(Auction::id).containsExactly(first.id(), second.id());

        Auction poor = Auction.listed(UUID.randomUUID(), bo, "Bo", sword, "Sword", Money.of(100), Money.ZERO, 60, 3);
        assertThat(book.listAuction(poor, Money.of(5_000), most, 100, 100).succeeded()).as("cannot pay the fee").isFalse();
        assertThat(book.auctions()).hasSize(2);
    }

    @Test
    @DisplayName("a frozen account cannot put anything up, even where listing is free")
    void frozenSeller() {
        book.freeze(seller, true);
        Auction auction = Auction.listed(UUID.randomUUID(), seller, "Sel", sword, "Sword", Money.of(100), Money.ZERO,
                120, 1);
        assertThat(book.listAuction(auction, Money.ZERO, most, 100, 100).succeeded()).isFalse();
        assertThat(book.auctions()).isEmpty();
    }

    @Test
    @DisplayName("the queue's limits hold under the ledger's lock, whatever two callers counted at once")
    void limits() {
        Auction one = Auction.listed(UUID.randomUUID(), seller, "Sel", sword, "Sword", Money.of(100), Money.ZERO, 120, 1);
        Auction two = Auction.listed(UUID.randomUUID(), seller, "Sel", sword, "Sword", Money.of(100), Money.ZERO, 120, 2);
        Auction other = Auction.listed(UUID.randomUUID(), ada, "Ada", sword, "Sword", Money.of(100), Money.ZERO, 120, 3);
        assertThat(book.listAuction(one, Money.ZERO, most, 10, 1).succeeded()).isTrue();
        assertThat(book.listAuction(two, Money.ZERO, most, 10, 1).succeeded()).as("one per player").isFalse();
        assertThat(book.listAuction(other, Money.ZERO, most, 1, 5).succeeded()).as("the queue is full").isFalse();
        assertThat(book.auctions()).hasSize(1);
    }

    @Test
    @DisplayName("a bid is held in escrow, and an outbid bidder gets every coin back at once")
    void bidding() {
        Auction auction = listed(100, 0);
        book.startNextAuction(clock.get());
        AuctionBid first = bid(auction, ada, "Ada", 100);
        assertThat(first.kind()).isEqualTo(AuctionBid.Kind.PLACED);
        assertThat(book.balance(ada)).isEqualTo(Money.of(900));
        assertThat(escrow()).isEqualTo(Money.of(100));

        AuctionBid second = bid(auction, bo, "Bo", 150);
        assertThat(second.kind()).isEqualTo(AuctionBid.Kind.PLACED);
        assertThat(second.outbid()).isEqualTo(ada);
        assertThat(second.refunded()).isEqualTo(Money.of(100));
        assertThat(book.balance(ada)).isEqualTo(Money.of(1_000));
        assertThat(book.balance(bo)).isEqualTo(Money.of(850));
        assertThat(escrow()).isEqualTo(Money.of(150));
        assertThat(second.auction().bidder()).isEqualTo(bo);
        assertThat(second.auction().bids()).isEqualTo(2);
    }

    @Test
    @DisplayName("refused: your own auction, too little, outbidding yourself, money you do not have")
    void refusals() {
        Auction auction = listed(100, 0);
        book.startNextAuction(clock.get());
        assertThat(bid(auction, seller, "Sel", 500).kind()).isEqualTo(AuctionBid.Kind.OWN);
        assertThat(bid(auction, ada, "Ada", 99).kind()).isEqualTo(AuctionBid.Kind.TOO_LOW);
        bid(auction, ada, "Ada", 100);
        assertThat(bid(auction, bo, "Bo", 105).kind()).as("must beat 100 by 10").isEqualTo(AuctionBid.Kind.TOO_LOW);
        assertThat(bid(auction, ada, "Ada", 300).kind()).isEqualTo(AuctionBid.Kind.TOP);
        AuctionBid broke = bid(auction, bo, "Bo", 5_000);
        assertThat(broke.kind()).isEqualTo(AuctionBid.Kind.REFUSED);
        assertThat(book.balance(bo)).isEqualTo(Money.of(1_000));
        assertThat(book.balance(ada)).as("Ada is still the top bidder").isEqualTo(Money.of(900));
        assertThat(escrow()).isEqualTo(Money.of(100));
    }

    @Test
    @DisplayName("a bid in the last seconds moves the end, as the caller decides")
    void extended() {
        Auction auction = listed(100, 0);
        book.startNextAuction(0);
        AuctionBid placed = book.bid(auction.id(), ada, "Ada", Money.of(100), minimum, end -> end + 15_000, 0, most);
        assertThat(placed.auction().endsAt()).isEqualTo(135_000);
    }

    @Test
    @DisplayName("the clock is the ledger's: no bid after the end, and no end before it — whatever a caller saw")
    void onTime() {
        Auction auction = listed(100, 0);
        book.startNextAuction(clock.get());
        long end = book.liveAuction().orElseThrow().endsAt();
        assertThat(book.endAuction(auction.id(), price -> Money.ZERO, end - 1)).as("still running").isEmpty();
        assertThat(book.bid(auction.id(), ada, "Ada", Money.of(100), minimum, at -> at, end, most).kind())
                .as("a bid at the end is too late").isEqualTo(AuctionBid.Kind.GONE);
        assertThat(book.balance(ada)).isEqualTo(Money.of(1_000));
        AuctionBid late = book.bid(auction.id(), bo, "Bo", Money.of(100), minimum, at -> at + 10_000, end - 1, most);
        assertThat(late.placed()).isTrue();
        assertThat(book.endAuction(auction.id(), price -> Money.ZERO, end)).as("the bid moved the end").isEmpty();
        assertThat(book.endAuction(auction.id(), price -> Money.of(price.minor() / 10), end + 10_000))
                .map(AuctionEnd::fee).as("the fee is taken from the final price").contains(Money.of(10));
    }

    @Test
    @DisplayName("sold: the seller is paid less the fee, the escrow empties, and the item is owed to the winner")
    void sold() {
        Auction auction = listed(100, 0);
        book.startNextAuction(clock.get());
        bid(auction, ada, "Ada", 100);
        bid(auction, bo, "Bo", 400);
        AuctionEnd end = book.endAuction(auction.id(), price -> Money.of(20), Long.MAX_VALUE).orElseThrow();
        assertThat(end.sold()).isTrue();
        assertThat(end.paid()).isEqualTo(Money.of(380));
        assertThat(book.balance(seller)).isEqualTo(Money.of(1_380));
        assertThat(escrow()).isEqualTo(Money.ZERO);
        assertThat(end.claim().player()).isEqualTo(bo);
        assertThat(end.claim().item()).containsExactly(sword);
        assertThat(end.claim().reason()).isEqualTo(AuctionClaim.Reason.WON);
        assertThat(book.claimsOf(bo)).hasSize(1);
        assertThat(book.auctions()).isEmpty();
        assertThat(book.endAuction(auction.id(), price -> Money.ZERO, Long.MAX_VALUE)).as("only once").isEmpty();
    }

    @Test
    @DisplayName("unsold or cancelled, the item goes back to the seller and a bid back to its bidder")
    void unsoldAndCancelled() {
        Auction nobody = listed(100, 0);
        book.startNextAuction(clock.get());
        AuctionEnd unsold = book.endAuction(nobody.id(), price -> Money.of(20), Long.MAX_VALUE).orElseThrow();
        assertThat(unsold.sold()).isFalse();
        assertThat(unsold.claim().player()).isEqualTo(seller);
        assertThat(unsold.claim().reason()).isEqualTo(AuctionClaim.Reason.UNSOLD);
        assertThat(book.balance(seller)).isEqualTo(Money.of(1_000));

        Auction stopped = listed(100, 0);
        book.startNextAuction(clock.get());
        bid(stopped, ada, "Ada", 200);
        AuctionEnd cancelled = book.cancelAuction(stopped.id()).orElseThrow();
        assertThat(cancelled.claim().player()).isEqualTo(seller);
        assertThat(cancelled.claim().reason()).isEqualTo(AuctionClaim.Reason.CANCELLED);
        assertThat(book.balance(ada)).isEqualTo(Money.of(1_000));
        assertThat(escrow()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("an outbid bidder whose account was frozen meanwhile is still paid back — nothing sticks in escrow")
    void frozenRefund() {
        Auction auction = listed(100, 0);
        book.startNextAuction(clock.get());
        bid(auction, ada, "Ada", 100);
        book.freeze(ada, true);
        bid(auction, bo, "Bo", 200);
        assertThat(book.balance(ada)).isEqualTo(Money.of(1_000));
        assertThat(escrow()).isEqualTo(Money.of(200));
    }

    @Test
    @DisplayName("the queue, the live auction with its bid, the escrow and the items owed survive a restart")
    void restart() {
        Auction live = listed(100, 0);
        Auction waiting = listed(50, 0);
        book.startNextAuction(clock.get());
        bid(live, ada, "Ada", 300);
        Auction gone = listed(10, 0);
        book.cancelAuction(gone.id());

        AccountBook fresh = reopened();
        assertThat(fresh.auctions()).extracting(Auction::id).containsExactly(live.id(), waiting.id());
        Auction back = fresh.liveAuction().orElseThrow();
        assertThat(back.bidder()).isEqualTo(ada);
        assertThat(back.bid()).isEqualTo(Money.of(300));
        assertThat(back.item()).containsExactly(sword);
        assertThat(fresh.balance(AccountBook.AUCTION_ESCROW)).isEqualTo(Money.of(300));
        List<AuctionClaim> owed = fresh.claimsOf(seller);
        assertThat(owed).hasSize(1);
        assertThat(fresh.takeClaim(owed.getFirst().id())).isTrue();
        assertThat(fresh.takeClaim(owed.getFirst().id())).as("only once").isFalse();

        book = fresh;
        AccountBook again = reopened();
        assertThat(again.claimsOf(seller)).isEmpty();
    }
}
