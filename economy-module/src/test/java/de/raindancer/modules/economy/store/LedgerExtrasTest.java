package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
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

/** What the ledger keeps for the anti-inflation tools: where money came from, today's takings, idle money. */
class LedgerExtrasTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
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
        book.open(bob, "Bob", Money.ZERO);
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

    @Test
    @DisplayName("another plugin's source is written with the line and read back on the statement")
    void source() {
        book.change(alice, Money.of(-50), TransactionKind.PLUGIN, "Upkeep", null, most, "claims.upkeep");
        AccountBook fresh = reopened();
        assertThat(fresh.history(alice, 1, 0).getFirst().source()).isEqualTo("claims.upkeep");
    }

    @Test
    @DisplayName("today's takings are counted per account and for everybody, survive a restart, and start again tomorrow")
    void today() {
        book.change(alice, Money.of(300), TransactionKind.SELL, "", null, most);
        book.change(bob, Money.of(200), TransactionKind.SELL, "", null, most);
        book.change(alice, Money.of(-100), TransactionKind.BUY, "", null, most);
        assertThat(book.today(alice, TransactionKind.SELL)).isEqualTo(Money.of(300));
        assertThat(book.todayByAll(TransactionKind.SELL)).isEqualTo(Money.of(500));

        AccountBook fresh = reopened();
        assertThat(fresh.today(alice, TransactionKind.SELL)).isEqualTo(Money.of(300));
        assertThat(fresh.todayByAll(TransactionKind.SELL)).isEqualTo(Money.of(500));

        clock.addAndGet(86_400_000L);
        assertThat(fresh.today(alice, TransactionKind.SELL)).isEqualTo(Money.ZERO);
        assertThat(fresh.todayByAll(TransactionKind.SELL)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("spending marks an account as active; being taxed does not; it survives a restart")
    void idle() {
        long opened = book.find(alice).orElseThrow().idleSince();
        clock.addAndGet(5_000);
        book.change(alice, Money.of(-10), TransactionKind.TAX, "", null, most);
        assertThat(book.find(alice).orElseThrow().idleSince()).isEqualTo(opened);
        clock.addAndGet(5_000);
        book.transfer(alice, bob, Money.of(10), Money.ZERO, TransactionKind.PAY, "", most);
        long spent = clock.get();
        assertThat(book.find(alice).orElseThrow().idleSince()).isEqualTo(spent);
        assertThat(reopened().find(alice).orElseThrow().idleSince()).isEqualTo(spent);
    }

    @Test
    @DisplayName("a week of the ledger says what printed money and what destroyed it, by kind and by plugin")
    void flows() {
        book.change(alice, Money.of(300), TransactionKind.REWARD, "Zombie", null, most);
        book.change(alice, Money.of(200), TransactionKind.PLUGIN, "Goal", null, most, "jobs.goal");
        book.change(alice, Money.of(-50), TransactionKind.PLUGIN, "Upkeep", null, most, "claims.upkeep");
        book.transfer(alice, bob, Money.of(100), Money.of(10), TransactionKind.PAY, "", most);
        var flows = book.flows(0);
        assertThat(flows.created()).containsEntry("Reward", 300L).containsEntry("jobs.goal", 200L)
                .containsEntry("Opening balance", 1_000L);
        assertThat(flows.destroyed()).containsEntry("claims.upkeep", 50L).containsEntry("Tax", 10L);
        assertThat(flows.created()).doesNotContainKey("Payment");
        assertThat(flows.destroyed()).doesNotContainKey("Payment");
    }
}
