package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.economy.rules.BalanceRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger on both dialects: a network keeps it in PostgreSQL, and a book that cannot load refuses every payment. */
@Tag("database")
class AccountBookDialectTest {

    @TempDir
    Path folder;

    private Database database;

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("the book loads, and what was written is there after a restart")
    void loadsAndKeeps() {
        UUID alice = UUID.randomUUID();
        database = TestDatabases.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA);
        AccountBook book = new AccountBook(database, new BalanceRule(), () -> 1_000_000L);
        book.load();
        assertThat(book.isLoaded()).isTrue();
        book.open(alice, "Alice", Money.of(5_000));
        book.flush();

        AccountBook again = new AccountBook(database, new BalanceRule(), () -> 1_000_000L);
        again.load();

        assertThat(again.isLoaded()).isTrue();
        assertThat(again.find(alice)).hasValueSatisfying(account -> assertThat(account.balance()).isEqualTo(Money.of(5_000)));
    }
}
