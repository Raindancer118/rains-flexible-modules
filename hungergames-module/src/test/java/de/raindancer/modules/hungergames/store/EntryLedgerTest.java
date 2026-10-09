package de.raindancer.modules.hungergames.store;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntryLedgerTest {

    @Test
    @DisplayName("what each entrant paid and the order they fell in survive a restart; clear empties both")
    void persists(@TempDir Path folder) {
        UUID ana = UUID.randomUUID();
        UUID bo = UUID.randomUUID();
        Path file = folder.resolve("entries.yml");
        EntryLedger ledger = new EntryLedger(file);
        ledger.load();
        assertThat(ledger.paid(ana, Money.of(500))).isTrue();
        assertThat(ledger.paid(bo, Money.of(700))).isTrue();
        assertThat(ledger.fell(bo)).isTrue();

        EntryLedger again = new EntryLedger(file);
        again.load();
        assertThat(again.pot()).isEqualTo(Money.of(1_200));
        assertThat(again.fallen()).containsExactly(bo);
        assertThat(again.take(ana)).contains(Money.of(500));
        assertThat(again.take(ana)).isEmpty();
        assertThat(again.pot()).isEqualTo(Money.of(700));
        assertThat(again.clear()).isTrue();
        EntryLedger empty = new EntryLedger(file);
        empty.load();
        assertThat(empty.pot()).isEqualTo(Money.ZERO);
        assertThat(empty.fallen()).isEqualTo(List.of());
    }
}
