package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.EconomyResult.Outcome;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.MoneySupply;
import de.raindancer.modules.economy.model.DailyClaim;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.model.TransactionKind;
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

/** A hard cap on money: nothing is printed, every payout comes out of the treasury and every fee goes back in. */
class TreasuryTest {

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
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("an open economy prints what it pays, as it always has")
    void openEconomy() {
        book.open(alice, "Alice", Money.of(3_000));
        assertThat(book.change(alice, Money.of(1_000_000), TransactionKind.REWARD, "", null, most).succeeded()).isTrue();
        MoneySupply supply = book.supply(1);
        assertThat(supply.capped()).isFalse();
        assertThat(supply.circulating()).isEqualTo(Money.of(1_003_000));
    }

    @Test
    @DisplayName("under a cap a payout comes out of the treasury, and one the treasury cannot cover moves nothing")
    void payoutsFromTheTreasury() {
        book.limitSupply(true, Money.of(10_000));
        book.open(alice, "Alice", Money.of(3_000));
        assertThat(book.supply(1).treasury()).isEqualTo(Money.of(7_000));

        assertThat(book.change(alice, Money.of(5_000), TransactionKind.REWARD, "", null, most).succeeded()).isTrue();
        EconomyResult refused = book.change(alice, Money.of(3_000), TransactionKind.REWARD, "", null, most);
        assertThat(refused.outcome()).isEqualTo(Outcome.TREASURY_EMPTY);
        assertThat(book.balance(alice)).isEqualTo(Money.of(8_000));
        assertThat(book.change(alice, Money.of(2_000), TransactionKind.REWARD, "", null, most).succeeded())
                .as("exactly what is left can be paid").isTrue();
        assertThat(book.supply(1).treasury()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("fees, taxes and purchases go back into the treasury, so it can pay out again")
    void sinksRefill() {
        book.limitSupply(true, Money.of(10_000));
        book.open(alice, "Alice", Money.of(10_000));
        book.open(bob, "Bob", Money.ZERO);
        assertThat(book.change(alice, Money.of(1), TransactionKind.REWARD, "", null, most).outcome())
                .isEqualTo(Outcome.TREASURY_EMPTY);

        book.change(alice, Money.of(-2_000), TransactionKind.BUY, "", null, most);
        book.transfer(alice, bob, Money.of(1_000), Money.of(100), TransactionKind.PAY, "", most);
        assertThat(book.supply(2).treasury()).isEqualTo(Money.of(2_100));
        assertThat(book.change(bob, Money.of(2_100), TransactionKind.INCOME, "", null, most).succeeded()).isTrue();
    }

    @Test
    @DisplayName("moving money between players, into cash and back changes nothing about how much there is")
    void movingIsNotPrinting() {
        book.limitSupply(true, Money.of(10_000));
        book.open(alice, "Alice", Money.of(6_000));
        book.open(bob, "Bob", Money.ZERO);
        book.transfer(alice, bob, Money.of(1_000), Money.ZERO, TransactionKind.PAY, "", most);
        book.issueCash(alice, Map.of("N1", Money.of(500)), Map.of(Money.of(100), 3), Money.of(800), Money.ZERO, most);
        assertThat(book.circulating()).isEqualTo(Money.of(6_000));
        book.redeemCash(bob, java.util.List.of("N1"), Map.of(Money.of(100), 3), Money.of(800), most);
        assertThat(book.circulating()).isEqualTo(Money.of(6_000));
        assertThat(book.supply(2).treasury()).isEqualTo(Money.of(4_000));
    }

    @Test
    @DisplayName("a scratch ticket is a record of a sale, not money, and does not count")
    void ticketsAreNotMoney() {
        book.limitSupply(true, Money.of(10_000));
        book.open(alice, "Alice", Money.of(1_000));
        book.buyTicket(alice, "T1", Money.of(100), most);
        assertThat(book.circulating()).isEqualTo(Money.of(900));
    }

    @Test
    @DisplayName("a new account gets what the treasury still has toward its starting balance, never more")
    void startingBalance() {
        book.limitSupply(true, Money.of(1_500));
        book.open(alice, "Alice", Money.of(1_000));
        book.open(bob, "Bob", Money.of(1_000));
        assertThat(book.balance(bob)).isEqualTo(Money.of(500));
    }

    @Test
    @DisplayName("daily rewards, winnings, staff gifts and loans all need room in the treasury")
    void everyFaucet() {
        book.limitSupply(true, Money.of(1_000));
        book.open(alice, "Alice", Money.of(1_000));
        DailyClaim claim = new DailyClaim(true, 1, 5, 0);
        assertThat(book.claimDaily(alice, claim, Money.of(10), most).outcome()).isEqualTo(Outcome.TREASURY_EMPTY);
        assertThat(book.play(alice, Money.of(100), Money.of(300), "Dice", most).outcome())
                .isEqualTo(Outcome.TREASURY_EMPTY);
        assertThat(book.balance(alice)).isEqualTo(Money.of(1_000));
        assertThat(book.play(alice, Money.of(100), Money.ZERO, "Dice", most).succeeded())
                .as("losing is always possible — it fills the treasury").isTrue();
        assertThat(book.play(alice, Money.of(100), Money.of(150), "Dice", most).succeeded())
                .as("a win the treasury can cover is paid").isTrue();
        assertThat(book.set(alice, Money.of(5_000), "staff").outcome()).isEqualTo(Outcome.TREASURY_EMPTY);
        assertThat(book.set(alice, Money.of(10), "staff").succeeded()).as("taking away is always possible").isTrue();
        Loan loan = new Loan(alice, "Alice", Money.of(5_000), Money.of(5_500), 0, 1, 1);
        assertThat(book.borrow(loan, most).outcome()).isEqualTo(Outcome.TREASURY_EMPTY);
        assertThat(book.loanOf(alice)).isEmpty();
    }

    @Test
    @DisplayName("with the cap lowered under what is out there, nothing is paid until enough has come back")
    void overTheCap() {
        book.open(alice, "Alice", Money.of(5_000));
        book.limitSupply(true, Money.of(3_000));
        MoneySupply supply = book.supply(1);
        assertThat(supply.overCap()).isEqualTo(Money.of(2_000));
        assertThat(book.change(alice, Money.of(1), TransactionKind.REWARD, "", null, most).outcome())
                .isEqualTo(Outcome.TREASURY_EMPTY);
        assertThat(book.change(alice, Money.of(-1_000), TransactionKind.FEE, "", null, most).succeeded()).isTrue();
    }

    @Test
    @DisplayName("switching the cap off makes it an open economy again at once")
    void switchedOff() {
        book.limitSupply(true, Money.of(100));
        book.open(alice, "Alice", Money.of(100));
        book.limitSupply(false, Money.of(100));
        assertThat(book.change(alice, Money.of(1_000), TransactionKind.REWARD, "", null, most).succeeded()).isTrue();
    }

    @Test
    @DisplayName("a refund gives back what a fee took even when somebody used the room in between")
    void refundIgnoresTheCap() {
        book.limitSupply(true, Money.of(1_000));
        book.open(alice, "Alice", Money.of(500));
        book.open(bob, "Bob", Money.of(500));
        book.change(alice, Money.of(-100), TransactionKind.PLUGIN, "Teleport", null, most, "tpa.fee");
        book.change(bob, Money.of(100), TransactionKind.REWARD, "", null, most);
        assertThat(book.restore(alice, Money.of(100), TransactionKind.PLUGIN, "Refund", "tpa.fee").succeeded()).isTrue();
        assertThat(book.balance(alice)).isEqualTo(Money.of(500));
    }

    @Test
    @DisplayName("a win the treasury cannot cover in full is paid as far as it can, never past the cap")
    void upTo() {
        book.limitSupply(true, Money.of(1_000));
        book.open(alice, "Alice", Money.of(900));
        EconomyResult paid = book.changeUpTo(alice, Money.of(300), TransactionKind.GAMBLE, "Mines — won", most);
        assertThat(paid.succeeded()).isTrue();
        assertThat(paid.amount()).isEqualTo(Money.of(100));
        assertThat(book.circulating()).isEqualTo(Money.of(1_000));
        assertThat(book.changeUpTo(alice, Money.of(300), TransactionKind.GAMBLE, "", most).outcome())
                .isEqualTo(Outcome.TREASURY_EMPTY);
    }

    @Test
    @DisplayName("a server raffle's money prize never prints past the cap")
    void serverRaffle() {
        book.limitSupply(true, Money.of(1_000));
        book.open(alice, "Alice", Money.of(1_000));
        var raffle = new de.raindancer.modules.economy.model.Raffle(UUID.randomUUID(), 1, null, "Server", null,
                "⛃500", Money.of(500), Money.of(10), 10, 10, 0, 1, java.util.Map.of());
        assertThat(book.startRaffle(raffle, Money.ZERO, most, 5, 5).succeeded()).isTrue();
        book.buyRaffleTickets(raffle.id(), alice, "Alice", 3, 0, most);
        clock.addAndGet(10);
        book.drawRaffle(raffle.id(), tickets -> alice, pot -> Money.ZERO, clock.get());
        assertThat(book.circulating()).as("only the 30 the tickets destroyed came back out").isEqualTo(Money.of(1_000));
        assertThat(book.balance(alice)).isEqualTo(Money.of(1_000));
    }
}
