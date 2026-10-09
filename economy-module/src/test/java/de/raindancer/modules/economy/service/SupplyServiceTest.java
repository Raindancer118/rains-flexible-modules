package de.raindancer.modules.economy.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.SupplySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.rules.StabilizerRule.Taps;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.EconomyDatabase;
import de.raindancer.modules.economy.store.SupplyBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyServiceTest {

    private static final long DAY = 86_400_000L;

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(100 * DAY);
    private final AtomicLong breadPrice = new AtomicLong(100);
    private final List<String> alerts = new ArrayList<>();
    private Database database;
    private AccountBook book;
    private SupplyService service;
    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA, () -> false);
        book = new AccountBook(database, new BalanceRule(), clock::get);
        book.zone(ZoneOffset.UTC);
        book.load();
        service = new SupplyService(book, new SupplyBook(database), material -> material.equals("BREAD")
                ? breadPrice.get() : 0, clock::get, ZoneOffset.UTC, alerts::add, EconomySettings.DEFAULTS);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private SupplySettings with(java.util.function.UnaryOperator<Map<String, Object>> change) {
        return SupplySettingsBuilder.from(SupplySettings.DEFAULTS, change);
    }

    @Test
    @DisplayName("with the shipped settings nothing is capped and no payout or fee changes")
    void defaults() {
        service.supply(SupplySettings.DEFAULTS);
        book.open(alice, "Alice", Money.of(1_000));
        service.refresh(1);
        assertThat(service.faucetChange("economy.reward")).isZero();
        assertThat(service.sinkChange("claims.upkeep")).isZero();
        assertThat(service.priceLevel()).isEqualTo(1.0);
        assertThat(book.supply(1).capped()).isFalse();
    }

    @Test
    @DisplayName("switching the cap on caps the ledger; a cap nobody can read leaves the economy open and says so")
    void cap() {
        service.supply(with(values -> {
            values.put("capped", true);
            values.put("cap", "10000");
            return values;
        }));
        assertThat(book.supply(0).capped()).isTrue();
        assertThat(book.supply(0).cap()).isEqualTo(Money.of(10_000));

        service.supply(with(values -> {
            values.put("capped", true);
            values.put("cap", "lots");
            return values;
        }));
        assertThat(book.supply(0).capped()).isFalse();
        assertThat(alerts).anyMatch(line -> line.contains("cap"));
    }

    @Test
    @DisplayName("a treasury running low turns payouts down through Core's levers")
    void lowTreasury() {
        service.supply(with(values -> {
            values.put("capped", true);
            values.put("cap", "10000");
            values.put("scaleBelowPercent", 20);
            values.put("lowTreasuryBrake", true);
            return values;
        }));
        book.open(alice, "Alice", Money.of(9_500));
        service.refresh(1);
        assertThat(service.faucetChange("economy.reward")).isEqualTo(-75);
    }

    @Test
    @DisplayName("once a day the basket is priced and written; the stabiliser turns the taps when prices run away")
    void daily() {
        service.supply(with(values -> {
            values.put("stabilizer", true);
            values.put("basket", List.of("bread 10"));
            return values;
        }));
        service.daily();
        clock.addAndGet(DAY);
        breadPrice.set(150);
        service.daily();
        assertThat(service.taps()).isEqualTo(new Taps(-5, 5));
        assertThat(service.faucetChange("economy.reward")).isEqualTo(-5);
        assertThat(service.sinkChange("claims.upkeep")).isEqualTo(5);
        assertThat(alerts).anyMatch(line -> line.contains("stabil"));

        service.daily();
        assertThat(service.taps()).as("once a day, not once a call").isEqualTo(new Taps(-5, 5));

        SupplyService restarted = new SupplyService(book, new SupplyBook(database), material -> 150, clock::get,
                ZoneOffset.UTC, alerts::add, EconomySettings.DEFAULTS);
        restarted.supply(with(values -> {
            values.put("stabilizer", true);
            return values;
        }));
        assertThat(restarted.taps()).as("kept across a restart").isEqualTo(new Taps(-5, 5));
    }

    @Test
    @DisplayName("fees follow the price index only when the owner says so")
    void index() {
        service.supply(with(values -> {
            values.put("basket", List.of("bread 10"));
            return values;
        }));
        service.daily();
        clock.addAndGet(DAY);
        breadPrice.set(200);
        service.daily();
        assertThat(service.priceLevel()).isEqualTo(1.0);
        assertThat(service.measuredLevel()).isEqualTo(2.0);
        service.supply(with(values -> {
            values.put("basket", List.of("bread 10"));
            values.put("feesFollow", true);
            return values;
        }));
        assertThat(service.priceLevel()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("the stabiliser switched off leaves the taps neutral, whatever they were")
    void stabilizerOff() {
        service.supply(with(values -> {
            values.put("stabilizer", true);
            values.put("basket", List.of("bread 10"));
            return values;
        }));
        service.daily();
        clock.addAndGet(DAY);
        breadPrice.set(500);
        service.daily();
        service.supply(SupplySettings.DEFAULTS);
        assertThat(service.faucetChange("economy.reward")).isZero();
    }

    @Test
    @DisplayName("the audit says when there is more money than the cap allows")
    void audit() {
        book.open(alice, "Alice", Money.of(50_000));
        service.supply(with(values -> {
            values.put("capped", true);
            values.put("cap", "10000");
            return values;
        }));
        service.refresh(1);
        assertThat(service.audit()).contains(Money.of(40_000));
        book.change(alice, Money.of(-45_000), TransactionKind.FEE, "", null, Money.of(Long.MAX_VALUE / 4));
        service.refresh(1);
        assertThat(service.audit()).isEmpty();
    }
}
