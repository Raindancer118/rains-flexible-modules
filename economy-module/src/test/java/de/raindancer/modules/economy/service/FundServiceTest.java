package de.raindancer.modules.economy.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Fund;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.service.FundService.Donation;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.EconomyDatabase;
import de.raindancer.modules.economy.store.SupplyBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FundServiceTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicBoolean on = new AtomicBoolean(true);
    private final List<String> filled = new ArrayList<>();
    private Database database;
    private AccountBook book;
    private FundService funds;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.load();
        book.open(alice, "Alice", Money.of(1_000));
        RainEconomy economy = new RainEconomy(book, id -> "Alice", EconomySettings.DEFAULTS);
        funds = new FundService(economy, new SupplyBook(database), clock::get, on::get,
                fund -> filled.add(fund.name()));
        funds.load();
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("staff start a fund; a name taken or an effect nobody can read is refused")
    void creating() {
        assertThat(funds.create("Spawn", Money.of(500), "boost 25 24")).isEmpty();
        assertThat(funds.create("spawn", Money.of(500), "")).contains("economy.fund.exists");
        assertThat(funds.create("Party", Money.of(500), "party time")).contains("economy.fund.bad-effect");
        assertThat(funds.create("Nothing", Money.ZERO, "")).contains("economy.not-an-amount");
        assertThat(funds.running()).extracting(Fund::name).containsExactly("Spawn");
    }

    @Test
    @DisplayName("a donation leaves the economy; the last one is cut to what is missing and fills the fund once")
    void donating() {
        funds.create("Spawn", Money.of(500), "boost 25 24");
        Money before = book.circulating();
        assertThat(funds.donate(alice, "spawn", Money.of(300))).isEqualTo(new Donation(Donation.Outcome.GIVEN, Money.of(300)));
        assertThat(funds.donate(alice, "Spawn", Money.of(400))).isEqualTo(new Donation(Donation.Outcome.FILLED, Money.of(200)));
        assertThat(book.balance(alice)).isEqualTo(Money.of(500));
        assertThat(book.circulating()).isEqualTo(before.minus(Money.of(500)));
        assertThat(filled).containsExactly("Spawn");
        assertThat(funds.donate(alice, "Spawn", Money.of(10)).outcome()).isEqualTo(Donation.Outcome.NO_SUCH_FUND);
    }

    @Test
    @DisplayName("more than the balance, an unknown fund, or funds switched off move nothing")
    void refusals() {
        funds.create("Spawn", Money.of(5_000), "");
        assertThat(funds.donate(alice, "Spawn", Money.of(2_000)).outcome()).isEqualTo(Donation.Outcome.NOT_ENOUGH);
        assertThat(funds.donate(alice, "Moon", Money.of(10)).outcome()).isEqualTo(Donation.Outcome.NO_SUCH_FUND);
        on.set(false);
        assertThat(funds.donate(alice, "Spawn", Money.of(10)).outcome()).isEqualTo(Donation.Outcome.OFF);
        assertThat(book.balance(alice)).isEqualTo(Money.of(1_000));
    }

    @Test
    @DisplayName("a full fund's boost raises every payout for its hours, then stops; it survives a restart")
    void boost() {
        funds.create("Spawn", Money.of(100), "boost 25 2");
        assertThat(funds.faucetChange("economy.reward")).isZero();
        funds.donate(alice, "Spawn", Money.of(100));
        assertThat(funds.faucetChange("economy.reward")).isEqualTo(25);

        FundService restarted = new FundService(new RainEconomy(book, id -> "Alice", EconomySettings.DEFAULTS),
                new SupplyBook(database), clock::get, on::get, fund -> { });
        restarted.load();
        assertThat(restarted.faucetChange("jobs.goal")).isEqualTo(25);
        clock.addAndGet(3 * 3_600_000L);
        assertThat(restarted.faucetChange("jobs.goal")).isZero();
        on.set(false);
        clock.set(1_000_000L);
        assertThat(funds.faucetChange("x")).as("switched off, no boost").isZero();
    }
}
