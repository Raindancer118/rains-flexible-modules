package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.EconomyResult.Outcome;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.rules.BalanceRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger's side of the new sinks: jumping the auction queue, and a season ending. */
class SinksBookTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Money most = Money.of(1_000_000_00L);
    private Database database;
    private AccountBook book;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.of(1_000));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Auction listed(UUID seller, String name) {
        clock.addAndGet(10);
        Auction auction = Auction.listed(UUID.randomUUID(), seller, "x", new byte[]{1}, name, Money.of(1), Money.ZERO,
                60, clock.get());
        book.listAuction(auction, Money.ZERO, most, 10, 5);
        return auction;
    }

    @Test
    @DisplayName("paying to jump the queue puts a waiting auction next, and the price is destroyed")
    void jump() {
        listed(bob, "first");
        listed(bob, "second");
        Auction mine = listed(alice, "third");
        Money before = book.circulating();
        assertThat(book.jumpQueue(mine.id(), alice, Money.of(100), most).succeeded()).isTrue();
        assertThat(book.balance(alice)).isEqualTo(Money.of(900));
        assertThat(book.circulating()).isEqualTo(before.minus(Money.of(100)));
        assertThat(book.startNextAuction(clock.get()).orElseThrow().itemName()).isEqualTo("third");
    }

    @Test
    @DisplayName("only the seller can jump, never a live auction, never one already next, never without the money")
    void refusals() {
        Auction first = listed(bob, "first");
        Auction second = listed(alice, "second");
        assertThat(book.jumpQueue(second.id(), bob, Money.of(100), most).outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(book.jumpQueue(first.id(), bob, Money.of(100), most).outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(book.jumpQueue(second.id(), alice, Money.of(5_000), most).outcome()).isEqualTo(Outcome.NOT_ENOUGH);
        book.startNextAuction(clock.get());
        Auction third = listed(bob, "third");
        assertThat(book.jumpQueue(first.id(), bob, Money.of(1), most).outcome()).as("live").isEqualTo(Outcome.REFUSED);
        assertThat(book.balance(bob)).isEqualTo(Money.of(1_000));
        assertThat(third).isNotNull();
    }

    @Test
    @DisplayName("a season's end turns balances into what the next season starts with, in one change, and says what each had")
    void season() {
        book.change(alice, Money.of(9_000), de.raindancer.modules.economy.model.TransactionKind.REWARD, "", null, most);
        Map<UUID, Money> had = book.endSeason(balance -> Money.of(500).plus(balance.share(0.1)), "Season 1 ended");
        assertThat(had).containsEntry(alice, Money.of(10_000)).containsEntry(bob, Money.of(1_000));
        assertThat(book.balance(alice)).isEqualTo(Money.of(1_500));
        assertThat(book.balance(bob)).isEqualTo(Money.of(600));
        assertThat(book.balance(AccountBook.LOTTERY_POT)).as("the server's own accounts are left alone")
                .isEqualTo(Money.ZERO);
    }
}
