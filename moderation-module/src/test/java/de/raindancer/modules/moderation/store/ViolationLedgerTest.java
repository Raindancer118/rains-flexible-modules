package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.moderation.model.CheckType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** A cheater's levels are one set of levels, however many servers add to them. */
@Tag("database")
class ViolationLedgerTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(10_000_000L);
    private final List<Database> databases = new ArrayList<>();
    private final UUID cheater = UUID.randomUUID();

    private ViolationLedger server() {
        Database database = TestDatabases.open(folder.resolve("anticheat.db"), AntiCheatDatabase.SCHEMA);
        databases.add(database);
        return new ViolationLedger(database, clock::get);
    }

    private double level(ViolationLedger ledger, CheckType check) {
        return ledger.levels(cheater).orElseThrow().getOrDefault(check, 0.0);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("what two servers add adds up, and the first write makes the row")
    void deltasAddUp() {
        ViolationLedger smp = server();
        ViolationLedger arena = server();

        smp.add(cheater, CheckType.GRIM_MOVEMENT, 2.0);
        smp.add(cheater, CheckType.GRIM_MOVEMENT, 1.5);
        arena.add(cheater, CheckType.GRIM_MOVEMENT, 4.0);
        arena.add(cheater, CheckType.GRIM_WORLD, 1.0);
        assertThat(smp.flush()).isTrue();
        assertThat(arena.flush()).isTrue();

        assertThat(level(smp, CheckType.GRIM_MOVEMENT)).isCloseTo(7.5, within(1e-6));
        assertThat(level(arena, CheckType.GRIM_WORLD)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("a level decays with time as the check decays, whichever server reads it")
    void decays() {
        ViolationLedger smp = server();
        ViolationLedger arena = server();
        smp.add(cheater, CheckType.GRIM_MOVEMENT, 10.0);
        smp.flush();

        clock.addAndGet(60_000);

        assertThat(level(arena, CheckType.GRIM_MOVEMENT)).isCloseTo(10.0 - CheckType.GRIM_MOVEMENT.decayPerMinute(), within(1e-6));
    }

    @Test
    @DisplayName("an add after some decay builds on the decayed level, not the old one")
    void addAfterDecay() {
        ViolationLedger smp = server();
        ViolationLedger arena = server();
        smp.add(cheater, CheckType.GRIM_MOVEMENT, 10.0);
        smp.flush();
        clock.addAndGet(60_000);

        arena.add(cheater, CheckType.GRIM_MOVEMENT, 3.0);
        arena.flush();

        assertThat(level(smp, CheckType.GRIM_MOVEMENT)).isCloseTo(13.0 - CheckType.GRIM_MOVEMENT.decayPerMinute(), within(1e-6));
    }

    @Test
    @DisplayName("a negative delta (a kick halves the level) takes off, and never goes below zero")
    void negativeDelta() {
        ViolationLedger smp = server();
        smp.add(cheater, CheckType.GRIM_MOVEMENT, 8.0);
        smp.flush();

        smp.add(cheater, CheckType.GRIM_MOVEMENT, -4.0);
        smp.flush();
        assertThat(level(smp, CheckType.GRIM_MOVEMENT)).isCloseTo(4.0, within(1e-6));

        smp.add(cheater, CheckType.GRIM_MOVEMENT, -100.0);
        smp.flush();
        assertThat(level(smp, CheckType.GRIM_MOVEMENT)).isZero();
    }

    @Test
    @DisplayName("a negative delta with no row yet makes nothing")
    void negativeFirst() {
        ViolationLedger smp = server();
        smp.add(cheater, CheckType.GRIM_MOVEMENT, -2.0);
        smp.flush();
        assertThat(smp.levels(cheater).orElseThrow()).isEmpty();
    }

    @Test
    @DisplayName("clearing wipes every check on every server and drops what was gathered before it")
    void clearWipes() {
        ViolationLedger smp = server();
        ViolationLedger arena = server();
        smp.add(cheater, CheckType.GRIM_MOVEMENT, 5.0);
        smp.add(cheater, CheckType.GRIM_WORLD, 5.0);
        smp.flush();

        smp.add(cheater, CheckType.GRIM_MOVEMENT, 1.0);
        smp.clear(cheater);
        smp.add(cheater, CheckType.GRIM_COMBAT, 2.0);
        smp.flush();

        assertThat(arena.levels(cheater).orElseThrow()).containsOnlyKeys(CheckType.GRIM_COMBAT);
    }

    @Test
    @DisplayName("a player nobody flagged has no levels, and another player's are not theirs")
    void otherPlayers() {
        ViolationLedger smp = server();
        smp.add(UUID.randomUUID(), CheckType.GRIM_MOVEMENT, 5.0);
        smp.flush();
        assertThat(smp.levels(cheater).orElseThrow()).isEmpty();
    }

    @Test
    @DisplayName("a database that cannot be written keeps what was gathered for the next try")
    void failedWriteIsKept() throws Exception {
        Path elsewhere = folder.resolve("down").resolve("anticheat.db");
        Files.createDirectories(elsewhere.getParent());
        Database down = TestDatabases.unusable(elsewhere, AntiCheatDatabase.SCHEMA);
        try {
            ViolationLedger ledger = new ViolationLedger(down, clock::get);
            ledger.add(cheater, CheckType.GRIM_MOVEMENT, 1.0);
            assertThat(ledger.flush()).isFalse();
            assertThat(ledger.flush()).as("still waiting to be written").isFalse();
            assertThat(ledger.levels(cheater)).isEmpty();
        } finally {
            down.close();
        }
    }
}
