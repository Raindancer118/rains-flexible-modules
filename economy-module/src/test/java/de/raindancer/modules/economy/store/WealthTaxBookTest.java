package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.TaxRun;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BalanceRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the wealth tax in the ledger")
class WealthTaxBookTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private Database database;
    private AccountBook book;
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        book.open(ada, "Ada", Money.of(10_000));
        book.open(bo, "Bo", Money.of(50));
        book.change(AccountBook.LOTTERY_POT, Money.of(5_000), TransactionKind.LOTTERY, "", null, Money.of(1_000_000_000L));
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("every player pays their share, frozen or not; the server's own accounts do not")
    void taxed() {
        book.freeze(ada, true);
        TaxRun run = book.wealthTax(balance -> Money.of(balance.minor() / 100), "Wealth tax 1%", clock.get());
        assertThat(book.balance(ada)).isEqualTo(Money.of(9_900));
        assertThat(book.balance(bo)).as("too little to owe anything").isEqualTo(Money.of(50));
        assertThat(book.balance(AccountBook.LOTTERY_POT)).isEqualTo(Money.of(5_000));
        assertThat(run.accounts()).isEqualTo(1);
        assertThat(run.total()).isEqualTo(Money.of(100));
        assertThat(book.history(ada, 1, 0).getFirst().kind()).isEqualTo(TransactionKind.TAX);
    }

    @Test
    @DisplayName("when it last ran survives a restart")
    void remembered() {
        assertThat(book.lastWealthTax()).isZero();
        book.wealthTax(balance -> Money.ZERO, "", 7_000L);
        book.markWealthTax(9_000L);
        book.flush();
        database.close();
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        AccountBook fresh = new AccountBook(database, new BalanceRule(), clock::get);
        fresh.load();
        assertThat(fresh.lastWealthTax()).isEqualTo(9_000L);
    }
}
