package de.raindancer.modules.economy.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.EconomyDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderboardServiceTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private Database database;
    private AccountBook book;

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
    @DisplayName("a Bedrock player is ranked like anybody else; the server's pots are not")
    void bedrockRanked() {
        UUID java = UUID.randomUUID();
        UUID bedrock = UUID.fromString("00000000-0000-0000-0009-01f2c3d4e5f6");
        book.open(java, "Kaspar", Money.of(5_000));
        book.open(bedrock, ".Bedrock", Money.of(126_310));
        book.open(AccountBook.LOTTERY_POT, "Lottery", Money.of(1_000_000));

        LeaderboardService leaderboard = new LeaderboardService(book, clock::get);

        assertThat(leaderboard.ranking()).extracting(Account::id).containsExactly(bedrock, java);
        assertThat(leaderboard.placeOf(bedrock)).isEqualTo(1);
    }
}
