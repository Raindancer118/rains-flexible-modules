package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Schema;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CreditHistory;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.rules.LoanRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** What an account ever took in and paid out, and how its loans ended — for life, whatever the statement keeps. */
class CreditBookTest {

    private static final long DAY = LoanRule.DAY;
    private static final Money MOST = Money.of(1_000_000_000L);

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(0);
    private final UUID alice = UUID.randomUUID();

    private Database open(Schema schema) {
        return Database.open(folder.resolve("economy.db"), schema, () -> false);
    }

    private AccountBook book(Database database) {
        AccountBook book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        return book;
    }

    @Test
    @DisplayName("every line counts, flushed or not, and the totals survive a restart and a trimmed statement")
    void totals() {
        Database database = open(EconomyDatabase.SCHEMA);
        AccountBook book = book(database);
        book.open(alice, "Alice", Money.of(100));
        book.change(alice, Money.of(500), TransactionKind.SELL, "", null, MOST);
        book.change(alice, Money.of(200).negate(), TransactionKind.BUY, "", null, MOST);

        CreditHistory before = book.credit(alice);
        assertThat(before.in(TransactionKind.SELL)).as("not flushed yet, counted all the same").isEqualTo(Money.of(500));
        assertThat(before.out(TransactionKind.BUY)).isEqualTo(Money.of(200));

        book.flush();
        book.change(alice, Money.of(50), TransactionKind.SELL, "", null, MOST);
        assertThat(book.credit(alice).in(TransactionKind.SELL)).isEqualTo(Money.of(550));
        book.flush();
        clock.set(1_000);
        assertThat(book.forgetHistoryBefore(2_000)).isPositive();
        database.close();

        Database again = open(EconomyDatabase.SCHEMA);
        CreditHistory after = book(again).credit(alice);
        assertThat(after.in(TransactionKind.SELL)).isEqualTo(Money.of(550));
        assertThat(after.out(TransactionKind.BUY)).isEqualTo(Money.of(200));
        assertThat(after.in(TransactionKind.OPENING)).isEqualTo(Money.of(100));
        again.close();
    }

    @Test
    @DisplayName("a loan paid off by its due date counts on time; one collected after it counts late")
    void loanRecord() {
        Database database = open(EconomyDatabase.SCHEMA);
        AccountBook book = book(database);
        book.open(alice, "Alice", Money.of(10_000));

        book.borrow(new Loan(alice, "Alice", Money.of(100), Money.of(110), 0, 7 * DAY, 0), MOST);
        clock.set(3 * DAY);
        book.repay(alice, Money.of(110));
        assertThat(book.credit(alice).repaidOnTime()).isEqualTo(1);

        book.borrow(new Loan(alice, "Alice", Money.of(100), Money.of(110), 3 * DAY, 10 * DAY, 3 * DAY), MOST);
        clock.set(12 * DAY);
        book.collectLoans(12 * DAY, 0.0, MOST);
        assertThat(book.loanOf(alice)).isEmpty();
        assertThat(book.credit(alice).repaidLate()).isEqualTo(1);
        book.flush();
        database.close();

        Database again = open(EconomyDatabase.SCHEMA);
        CreditHistory after = book(again).credit(alice);
        assertThat(after.repaidOnTime()).isEqualTo(1);
        assertThat(after.repaidLate()).isEqualTo(1);
        again.close();
    }

    @Test
    @DisplayName("a server that already has a statement starts with totals worked out from it")
    void backfilledFromTheStatement() {
        int before = EconomyDatabase.FIRST_CREDIT_STEP;
        Database old = open(new Schema(EconomyDatabase.SCHEMA.steps().subList(0, before)));
        AccountBook book = new AccountBook(old, new BalanceRule(), clock::get);
        // The old book cannot know the new tables; write the statement it would have written by hand.
        old.write(connection -> {
            try (var insert = connection.prepareStatement(
                    "INSERT INTO ledger (at, account, other, delta, balance, kind, reason) VALUES (0, ?, NULL, ?, 0, ?, '')")) {
                insert.setString(1, alice.toString());
                insert.setLong(2, 700);
                insert.setString(3, "SELL");
                insert.executeUpdate();
                insert.setLong(2, -300);
                insert.setString(3, "GAMBLE");
                insert.executeUpdate();
            }
        });
        old.close();

        Database upgraded = open(EconomyDatabase.SCHEMA);
        CreditHistory found = book(upgraded).credit(alice);
        assertThat(found.in(TransactionKind.SELL)).isEqualTo(Money.of(700));
        assertThat(found.out(TransactionKind.GAMBLE)).isEqualTo(Money.of(300));
        upgraded.close();
    }

    @Test
    @DisplayName("nobody's history yet is an empty one")
    void empty() {
        Database database = open(EconomyDatabase.SCHEMA);
        assertThat(book(database).credit(UUID.randomUUID())).isEqualTo(CreditHistory.EMPTY);
        database.close();
    }

    @Test
    @DisplayName("'lately' is the last hours of playtime, counted by the book, and survives a restart")
    void recentPlaytime() {
        Database database = open(EconomyDatabase.SCHEMA);
        AccountBook book = book(database);
        AtomicLong minutes = new AtomicLong();
        book.playtime(id -> minutes.get());
        book.open(alice, "Alice", Money.of(100_000));

        book.change(alice, Money.of(5_000).negate(), TransactionKind.GAMBLE, "", null, MOST);   // hour 0
        book.flush();
        minutes.set(5 * 60);
        book.change(alice, Money.of(1_000).negate(), TransactionKind.GAMBLE, "", null, MOST);   // hour 5
        book.change(alice, Money.of(300), TransactionKind.GAMBLE, "", null, MOST);
        book.change(alice, Money.of(200), TransactionKind.SELL, "", null, MOST);

        CreditHistory.Recent last3 = book.credit(alice, 3).recent();
        assertThat(last3.staked()).as("hour 0 is more than three hours of play ago").isEqualTo(Money.of(1_000));
        assertThat(last3.won()).isEqualTo(Money.of(300));
        assertThat(last3.earned()).isEqualTo(Money.of(200));
        assertThat(book.credit(alice, 12).recent().staked()).isEqualTo(Money.of(6_000));

        book.flush();
        database.close();
        Database again = open(EconomyDatabase.SCHEMA);
        AccountBook reopened = book(again);
        reopened.playtime(id -> minutes.get());
        assertThat(reopened.credit(alice, 3).recent().staked()).isEqualTo(Money.of(1_000));
        assertThat(reopened.credit(alice, 12).recent().staked()).isEqualTo(Money.of(6_000));
        again.close();
    }
}
