package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.AuctionClaim;
import de.raindancer.modules.economy.model.Raffle;
import de.raindancer.modules.economy.model.RaffleBuy;
import de.raindancer.modules.economy.model.RaffleDraw;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Raffles in the ledger: the prize and every ticket's money are held until the draw, and the queue of
 * tickets survives a restart.
 */
@DisplayName("raffles in the ledger")
class RaffleBookTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Money most = Money.of(1_000_000_00L);
    private Database database;
    private AccountBook book;
    private final UUID host = UUID.randomUUID();
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final byte[] sword = {7, 7, 7};
    private final Function<Money, Money> fivePercent = pot -> Money.of(pot.minor() * 5 / 100);
    private final Function<Map<UUID, Integer>, UUID> first = tickets -> tickets.keySet().iterator().next();

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        book.open(host, "Host", Money.of(5_000));
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

    private Raffle itemRaffle(int mostTickets, int perPlayer) {
        Raffle raffle = Raffle.item(UUID.randomUUID(), book.nextRaffleNumber(), host, "Host", sword, "Diamond Sword",
                Money.of(10), mostTickets, perPlayer, clock.get(), clock.get() + 60_000);
        assertThat(book.startRaffle(raffle, Money.of(1_000), most).succeeded()).isTrue();
        return raffle;
    }

    private RaffleBuy buy(Raffle raffle, UUID who, String name, int count) {
        return book.buyRaffleTickets(raffle.id(), who, name, count, clock.get(), most);
    }

    private Money pot() {
        return book.balance(AccountBook.RAFFLE_POT);
    }

    @Test
    @DisplayName("starting takes the fee, numbers the raffle, and a frozen host cannot start one")
    void starting() {
        Raffle one = itemRaffle(0, 0);
        assertThat(one.number()).isEqualTo(1);
        assertThat(book.balance(host)).isEqualTo(Money.of(4_000));
        assertThat(book.nextRaffleNumber()).isEqualTo(2);
        book.freeze(ada, true);
        Raffle frozen = Raffle.item(UUID.randomUUID(), 2, ada, "Ada", sword, "Sword", Money.of(10), 0, 0,
                clock.get(), clock.get() + 60_000);
        assertThat(book.startRaffle(frozen, Money.ZERO, most).succeeded()).isFalse();
        assertThat(book.raffles()).extracting(Raffle::id).containsExactly(one.id());
    }

    @Test
    @DisplayName("tickets are paid into the pot, within the limits, and not by the host or after the end")
    void tickets() {
        Raffle raffle = itemRaffle(10, 4);
        RaffleBuy three = buy(raffle, ada, "Ada", 3);
        assertThat(three.kind()).isEqualTo(RaffleBuy.Kind.BOUGHT);
        assertThat(three.bought()).isEqualTo(3);
        assertThat(book.balance(ada)).isEqualTo(Money.of(970));
        assertThat(pot()).isEqualTo(Money.of(30));

        assertThat(buy(raffle, ada, "Ada", 5).bought()).as("one more for Ada, then her limit").isEqualTo(1);
        assertThat(buy(raffle, ada, "Ada", 1).kind()).isEqualTo(RaffleBuy.Kind.LIMIT);
        assertThat(buy(raffle, bo, "Bo", 4).bought()).isEqualTo(4);
        assertThat(buy(raffle, bo, "Bo", 1).kind()).isEqualTo(RaffleBuy.Kind.LIMIT);
        assertThat(book.raffle(raffle.id()).orElseThrow().sold()).isEqualTo(8);
        assertThat(buy(raffle, host, "Host", 1).kind()).isEqualTo(RaffleBuy.Kind.OWN);

        UUID cy = UUID.randomUUID();
        book.open(cy, "Cy", Money.of(5));
        RaffleBuy broke = buy(raffle, cy, "Cy", 1);
        assertThat(broke.kind()).isEqualTo(RaffleBuy.Kind.REFUSED);
        assertThat(book.balance(cy)).isEqualTo(Money.of(5));

        clock.addAndGet(60_000);
        assertThat(buy(raffle, bo, "Bo", 1).kind()).as("over").isEqualTo(RaffleBuy.Kind.GONE);
    }

    @Test
    @DisplayName("the draw: the winner is owed the item, the host is paid the pot less the fee")
    void draw() {
        Raffle raffle = itemRaffle(0, 0);
        buy(raffle, ada, "Ada", 2);
        buy(raffle, bo, "Bo", 8);
        RaffleDraw drawn = book.drawRaffle(raffle.id(), first, fivePercent, Long.MAX_VALUE).orElseThrow();
        assertThat(drawn.winner()).isEqualTo(ada);
        assertThat(drawn.claim().player()).isEqualTo(ada);
        assertThat(drawn.claim().reason()).isEqualTo(AuctionClaim.Reason.RAFFLE);
        assertThat(drawn.paid()).isEqualTo(Money.of(95));
        assertThat(drawn.fee()).isEqualTo(Money.of(5));
        assertThat(book.balance(host)).isEqualTo(Money.of(4_095));
        assertThat(pot()).isEqualTo(Money.ZERO);
        assertThat(book.raffles()).isEmpty();
        assertThat(book.drawRaffle(raffle.id(), first, fivePercent, Long.MAX_VALUE)).as("only once").isEmpty();
    }

    @Test
    @DisplayName("no draw before the end, whatever a caller saw")
    void onTime() {
        Raffle raffle = itemRaffle(0, 0);
        assertThat(book.drawRaffle(raffle.id(), first, fivePercent, raffle.endsAt() - 1)).isEmpty();
        assertThat(book.raffles()).hasSize(1);
        assertThat(book.drawRaffle(raffle.id(), first, fivePercent, raffle.endsAt())).isPresent();
    }

    @Test
    @DisplayName("nobody bought: the item goes back to the host")
    void nobody() {
        Raffle raffle = itemRaffle(0, 0);
        RaffleDraw drawn = book.drawRaffle(raffle.id(), first, fivePercent, Long.MAX_VALUE).orElseThrow();
        assertThat(drawn.winner()).isNull();
        assertThat(drawn.claim().player()).isEqualTo(host);
        assertThat(drawn.claim().reason()).isEqualTo(AuctionClaim.Reason.UNSOLD);
    }

    @Test
    @DisplayName("a server raffle pays its prize to the winner, and the tickets' money leaves the economy")
    void serverRaffle() {
        Raffle raffle = Raffle.server(UUID.randomUUID(), book.nextRaffleNumber(), Money.of(5_000), Money.of(10), 0, 0,
                clock.get(), clock.get() + 60_000);
        assertThat(book.startRaffle(raffle, Money.ZERO, most).succeeded()).isTrue();
        buy(raffle, ada, "Ada", 3);
        RaffleDraw drawn = book.drawRaffle(raffle.id(), first, fivePercent, Long.MAX_VALUE).orElseThrow();
        assertThat(drawn.claim()).isNull();
        assertThat(book.balance(ada)).isEqualTo(Money.of(970 + 5_000));
        assertThat(pot()).isEqualTo(Money.ZERO);
    }

    private Raffle moneyRaffle(long prize) {
        Raffle raffle = Raffle.money(UUID.randomUUID(), book.nextRaffleNumber(), host, "Host", Money.of(prize),
                Money.of(10), 0, 0, clock.get(), clock.get() + 60_000);
        assertThat(book.startRaffle(raffle, Money.ZERO, most).succeeded()).isTrue();
        return raffle;
    }

    @Test
    @DisplayName("a player raffles off money: it leaves their account at the start and reaches the winner at the draw")
    void playerMoney() {
        Raffle raffle = moneyRaffle(2_000);
        assertThat(book.balance(host)).isEqualTo(Money.of(3_000));
        assertThat(pot()).isEqualTo(Money.of(2_000));
        buy(raffle, ada, "Ada", 3);
        RaffleDraw drawn = book.drawRaffle(raffle.id(), first, fivePercent, Long.MAX_VALUE).orElseThrow();
        assertThat(drawn.claim()).isNull();
        assertThat(book.balance(ada)).isEqualTo(Money.of(970 + 2_000));
        assertThat(drawn.paid()).as("the tickets, less 5%").isEqualTo(Money.of(29));
        assertThat(book.balance(host)).isEqualTo(Money.of(3_029));
        assertThat(pot()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("money a player cannot cover is not raffled off")
    void playerMoneyTooMuch() {
        Raffle raffle = Raffle.money(UUID.randomUUID(), 1, host, "Host", Money.of(6_000), Money.of(10), 0, 0,
                clock.get(), clock.get() + 60_000);
        assertThat(book.startRaffle(raffle, Money.ZERO, most).succeeded()).isFalse();
        assertThat(book.balance(host)).isEqualTo(Money.of(5_000));
        assertThat(book.raffles()).isEmpty();
    }

    @Test
    @DisplayName("a player's money raffle nobody bought into, or one called off, gives the money back")
    void playerMoneyBack() {
        Raffle nobody = moneyRaffle(1_000);
        book.drawRaffle(nobody.id(), first, fivePercent, Long.MAX_VALUE);
        assertThat(book.balance(host)).isEqualTo(Money.of(5_000));
        Raffle off = moneyRaffle(1_000);
        buy(off, bo, "Bo", 2);
        book.cancelRaffle(off.id());
        assertThat(book.balance(host)).isEqualTo(Money.of(5_000));
        assertThat(book.balance(bo)).isEqualTo(Money.of(1_000));
        assertThat(pot()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("called off: every ticket is paid back and the item goes back to the host")
    void cancelled() {
        Raffle raffle = itemRaffle(0, 0);
        buy(raffle, ada, "Ada", 2);
        buy(raffle, bo, "Bo", 5);
        RaffleDraw off = book.cancelRaffle(raffle.id()).orElseThrow();
        assertThat(off.claim().player()).isEqualTo(host);
        assertThat(book.balance(ada)).isEqualTo(Money.of(1_000));
        assertThat(book.balance(bo)).isEqualTo(Money.of(1_000));
        assertThat(pot()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("a running raffle, its tickets and its pot survive a restart")
    void restart() {
        Raffle raffle = itemRaffle(50, 0);
        buy(raffle, ada, "Ada", 2);
        buy(raffle, bo, "Bo", 3);
        buy(raffle, ada, "Ada", 1);
        AccountBook fresh = reopened();
        Raffle back = fresh.raffle(raffle.id()).orElseThrow();
        assertThat(back.number()).isEqualTo(1);
        assertThat(back.item()).containsExactly(sword);
        assertThat(back.mostTickets()).isEqualTo(50);
        assertThat(back.ticketsOf(ada)).isEqualTo(3);
        assertThat(back.ticketsOf(bo)).isEqualTo(3);
        assertThat(fresh.balance(AccountBook.RAFFLE_POT)).isEqualTo(Money.of(60));
        assertThat(fresh.nextRaffleNumber()).isEqualTo(2);
    }
}
