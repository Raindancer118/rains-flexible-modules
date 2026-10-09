package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.FineRecord;
import de.raindancer.modules.moderation.store.FineLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fines kept across a restart, debt settled oldest first, forgiving and revoking. */
class FineLedgerTest {

    private static final UUID BO = UUID.randomUUID();

    @TempDir
    Path folder;

    private static FineRecord fine(String id, long at, long debt) {
        return new FineRecord(id, BO, "p-" + id, "fine", "griefing", at, 1_000, 1_000 - debt, 0, null, debt, 0, false, 0);
    }

    @Test
    @DisplayName("what is owed survives a restart")
    void persists() {
        FineLedger ledger = new FineLedger(folder.resolve("fines.yml"));
        ledger.add(fine("a", 1, 400));

        FineLedger reloaded = new FineLedger(folder.resolve("fines.yml"));
        reloaded.load();
        assertThat(reloaded.owed(BO)).isEqualTo(400);
        assertThat(reloaded.forPunishment("p-a")).hasSize(1);
        assertThat(reloaded.get("a").orElseThrow().victim()).isNull();
    }

    @Test
    @DisplayName("a payment settles the oldest debt first and never more than is owed")
    void payDownOldestFirst() {
        FineLedger ledger = new FineLedger(folder.resolve("fines.yml"));
        ledger.add(fine("old", 1, 300));
        ledger.add(fine("new", 2, 500));

        assertThat(ledger.payDown(BO, 400)).isEqualTo(400);
        assertThat(ledger.get("old").orElseThrow().debt()).isZero();
        assertThat(ledger.get("new").orElseThrow().debt()).isEqualTo(400);
        assertThat(ledger.payDown(BO, 5_000)).as("only what was owed is settled").isEqualTo(400);
        assertThat(ledger.owed(BO)).isZero();
    }

    @Test
    @DisplayName("forgiving writes off every debt and keeps the fines on record")
    void forgive() {
        FineLedger ledger = new FineLedger(folder.resolve("fines.yml"));
        ledger.add(fine("a", 1, 300));
        ledger.add(fine("b", 2, 200));

        assertThat(ledger.forgive(BO)).isEqualTo(500);
        assertThat(ledger.owed(BO)).isZero();
        assertThat(ledger.of(BO)).hasSize(2);
        assertThat(ledger.get("a").orElseThrow().forgiven()).isEqualTo(300);
    }

    @Test
    @DisplayName("a revoked fine owes nothing any more")
    void revokedOwesNothing() {
        FineLedger ledger = new FineLedger(folder.resolve("fines.yml"));
        ledger.add(fine("a", 1, 300));
        ledger.replace(ledger.get("a").orElseThrow().revokedWith(700));

        assertThat(ledger.owed(BO)).isZero();
        assertThat(ledger.get("a").orElseThrow().revoked()).isTrue();
        assertThat(ledger.get("a").orElseThrow().refunded()).isEqualTo(700);
    }
}
