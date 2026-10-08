package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Loan;
import de.raindancer.modules.economy.model.LoanCollection;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.rules.LoanRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Loans in the ledger: borrowing, paying back, the bank collecting, and all of it surviving a restart. */
class LoanBookTest {

    private static final long DAY = LoanRule.DAY;

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(0);
    private final Money most = Money.of(1_000_000_000L);
    private Database database;
    private AccountBook book;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        book.open(alice, "Alice", Money.of(100));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Loan loan(long borrowed, long owed) {
        return new Loan(alice, "Alice", Money.of(borrowed), Money.of(owed), 0, 7 * DAY, 0);
    }

    @Test
    @DisplayName("borrowing pays the money in and opens the loan; a second loan is refused")
    void borrowing() {
        EconomyResult taken = book.borrow(loan(1_000, 1_100), most);
        assertThat(taken.succeeded()).isTrue();
        assertThat(book.balance(alice)).isEqualTo(Money.of(1_100));
        assertThat(book.loanOf(alice)).map(Loan::owed).contains(Money.of(1_100));
        assertThat(book.borrow(loan(500, 550), most).succeeded()).isFalse();
        assertThat(book.balance(alice)).isEqualTo(Money.of(1_100));
    }

    @Test
    @DisplayName("paying back takes at most what is owed and what the balance holds; paid off closes the loan")
    void repaying() {
        book.borrow(loan(1_000, 1_100), most);
        EconomyResult part = book.repay(alice, Money.of(600));
        assertThat(part.succeeded()).isTrue();
        assertThat(book.loanOf(alice)).map(Loan::owed).contains(Money.of(500));
        assertThat(book.balance(alice)).isEqualTo(Money.of(500));
        book.repay(alice, Money.of(99_999));
        assertThat(book.loanOf(alice)).isEmpty();
        assertThat(book.balance(alice)).isEqualTo(Money.ZERO);
        assertThat(book.repay(alice, Money.of(1)).succeeded()).as("nothing owed").isFalse();
    }

    @Test
    @DisplayName("an overdue loan is collected from the balance, late fees first, and closes when covered")
    void collecting() {
        book.borrow(loan(1_000, 1_100), most);
        book.change(alice, Money.of(1_000).negate(), TransactionKind.BUY, "spent it", null, most);   // 100 left
        assertThat(book.collectLoans(6 * DAY, 0.02, most)).as("not due").isEmpty();

        List<LoanCollection> due = book.collectLoans(8 * DAY, 0.02, most);
        assertThat(due).hasSize(1);
        assertThat(due.getFirst().feeAdded()).isEqualTo(Money.of(22));
        assertThat(due.getFirst().taken()).isEqualTo(Money.of(100));
        assertThat(due.getFirst().cleared()).isFalse();
        assertThat(book.loanOf(alice)).map(Loan::owed).contains(Money.of(1_022));
        assertThat(book.balance(alice)).isEqualTo(Money.ZERO);

        assertThat(book.collectLoans(8 * DAY + 60_000, 0.02, most)).as("nothing to take, no new day").isEmpty();

        book.change(alice, Money.of(5_000), TransactionKind.SELL, "", null, most);
        List<LoanCollection> paid = book.collectLoans(8 * DAY + 120_000, 0.02, most);
        assertThat(paid.getFirst().taken()).isEqualTo(Money.of(1_022));
        assertThat(paid.getFirst().cleared()).isTrue();
        assertThat(book.loanOf(alice)).isEmpty();
        assertThat(book.balance(alice)).isEqualTo(Money.of(3_978));
    }

    @Test
    @DisplayName("staff can forgive a loan")
    void forgiving() {
        book.borrow(loan(1_000, 1_100), most);
        assertThat(book.forgive(alice)).isTrue();
        assertThat(book.loanOf(alice)).isEmpty();
        assertThat(book.forgive(alice)).isFalse();
    }

    @Test
    @DisplayName("a loan survives a restart, and a closed one stays closed")
    void survives() {
        book.borrow(loan(1_000, 1_100), most);
        book.repay(alice, Money.of(100));
        book.flush();
        database.close();
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        assertThat(book.loanOf(alice)).map(Loan::owed).contains(Money.of(1_000));
        assertThat(book.loanOf(alice)).map(Loan::dueAt).contains(7 * DAY);

        book.forgive(alice);
        book.flush();
        database.close();
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        assertThat(book.loanOf(alice)).isEmpty();
    }
}
