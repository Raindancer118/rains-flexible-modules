package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.economy.model.Fund;
import de.raindancer.modules.economy.rules.MarketRule;
import de.raindancer.modules.economy.rules.StabilizerRule.Taps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The market pressure and the anti-inflation tables on both dialects: reads, upserts and deletes. */
@Tag("database")
@DisplayName("market and supply books in the database")
class MarketAndSupplyBooksTest {

    @TempDir
    Path folder;

    private Database database;
    private final AtomicLong clock = new AtomicLong(1_000);

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("pressure is upserted, kept across a reopen and calmed to zero")
    void market() {
        MarketBook market = new MarketBook(database, new MarketRule(), clock::get);
        market.traded("DIAMOND", 64, 64, 0.5, true, 24);
        market.traded("COAL", 64, 64, 0.25, false, 24);
        market.flush();
        market.traded("DIAMOND", 64, 64, 0.5, true, 24);
        market.flush();

        MarketBook again = new MarketBook(database, new MarketRule(), clock::get);
        again.load();
        assertThat(again.stored("DIAMOND")).hasValue(1.0);
        assertThat(again.stored("COAL")).hasValue(-0.25);
        assertThat(again.stored("DIRT")).isEmpty();

        again.calm();
        again.flush();
        MarketBook calm = new MarketBook(database, new MarketRule(), clock::get);
        calm.load();
        assertThat(calm.stored("DIAMOND")).hasValue(0.0);
        assertThat(calm.stored("COAL")).hasValue(0.0);
    }

    @Test
    @DisplayName("supply days upsert, are read from a day on, and the first priced basket is found")
    void supplyDays() {
        SupplyBook book = new SupplyBook(database);
        assertThat(book.firstBasket()).isEmpty();
        assertThat(book.recordDay(10, Money.of(500), Money.of(900), 0)).isTrue();
        assertThat(book.recordDay(11, Money.of(600), Money.of(900), 40)).isTrue();
        assertThat(book.recordDay(12, Money.of(700), Money.of(900), 50)).isTrue();
        assertThat(book.recordDay(12, Money.of(750), Money.of(950), 55)).isTrue();

        assertThat(book.circulatingDays(11)).containsExactly(Map.entry(11L, 600L), Map.entry(12L, 750L));
        assertThat(book.basketDays(10)).containsExactly(Map.entry(10L, 0L), Map.entry(11L, 40L), Map.entry(12L, 55L));
        assertThat(book.firstBasket()).hasValue(Map.entry(11L, 40L));
    }

    @Test
    @DisplayName("the stabilizer taps default to neutral, then upsert in place")
    void stabilizer() {
        SupplyBook book = new SupplyBook(database);
        assertThat(book.stabilizer().taps()).isEqualTo(Taps.NEUTRAL);
        assertThat(book.stabilizer().day()).isEqualTo(Long.MIN_VALUE);
        assertThat(book.saveStabilizer(new Taps(2, -1), 20_000)).isTrue();
        assertThat(book.saveStabilizer(new Taps(3, 4), 20_001)).isTrue();
        assertThat(book.stabilizer().taps()).isEqualTo(new Taps(3, 4));
        assertThat(book.stabilizer().day()).isEqualTo(20_001);
    }

    @Test
    @DisplayName("funds are saved, updated, listed oldest first and removed")
    void funds() {
        SupplyBook book = new SupplyBook(database);
        Fund later = new Fund(UUID.randomUUID(), "Bridge", Money.of(1_000), Money.ZERO, "bridge", 200, 0);
        Fund earlier = new Fund(UUID.randomUUID(), "Spawn", Money.of(500), Money.of(10), "spawn", 100, 0);
        assertThat(book.saveFund(later)).isTrue();
        assertThat(book.saveFund(earlier)).isTrue();
        assertThat(book.funds()).extracting(Fund::name).containsExactly("Spawn", "Bridge");

        assertThat(book.saveFund(new Fund(later.id(), "Bridge", Money.of(1_000), Money.of(1_000), "bridge", 200, 999))).isTrue();
        Fund done = book.funds().get(1);
        assertThat(done.raised()).isEqualTo(Money.of(1_000));
        assertThat(done.doneAt()).isEqualTo(999);

        assertThat(book.removeFund(earlier.id())).isTrue();
        assertThat(book.funds()).extracting(Fund::name).containsExactly("Bridge");
    }

    @Test
    @DisplayName("gambler insurance is idempotent to switch on and off")
    void insurance() {
        SupplyBook book = new SupplyBook(database);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertThat(book.insureGambler(alice, true)).isTrue();
        assertThat(book.insureGambler(alice, true)).isTrue();
        assertThat(book.insureGambler(bob, true)).isTrue();
        assertThat(book.insuredGamblers()).containsExactlyInAnyOrder(alice, bob);
        assertThat(book.insureGambler(alice, false)).isTrue();
        assertThat(book.insuredGamblers()).containsExactly(bob);
    }

    @Test
    @DisplayName("a season ends with its points written once and the next season begun")
    void seasons() {
        SupplyBook book = new SupplyBook(database);
        UUID alice = UUID.randomUUID();
        assertThat(book.season()).isEqualTo(1);
        assertThat(book.endSeason(1, Map.of(alice, 40L))).isTrue();
        assertThat(book.endSeason(1, Map.of(alice, 45L))).isTrue();
        assertThat(book.season()).isEqualTo(2);
        assertThat(book.endSeason(2, Map.of(alice, 7L))).isTrue();
        assertThat(book.season()).isEqualTo(3);
        assertThat(book.pointsOf(alice)).containsExactly(Map.entry(1, 45L), Map.entry(2, 7L));
    }
}
