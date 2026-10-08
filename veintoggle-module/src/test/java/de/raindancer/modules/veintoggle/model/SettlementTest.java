package de.raindancer.modules.veintoggle.model;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.veintoggle.model.Settlement.Source;
import de.raindancer.modules.veintoggle.model.Settlement.Taking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What an undo took, and what it gives back when not all of it was needed: the last taken first. */
class SettlementTest {

    private final UUID ada = UUID.randomUUID();
    private final UUID miner = UUID.randomUUID();

    @Test
    @DisplayName("the surplus goes back newest first — the undoer's money before a collector's items before the ground")
    void refundOrder() {
        Settlement<String> settlement = new Settlement<>();
        settlement.add(new Taking<>(Source.GROUND, null, "diamond", 2, Money.ZERO));
        settlement.add(new Taking<>(Source.COLLECTOR_ITEMS, ada, "diamond", 2, Money.ZERO));
        settlement.add(new Taking<>(Source.UNDOER_MONEY, miner, "diamond", 1, Money.of(1_000)));

        assertThat(settlement.supply()).containsExactlyEntriesOf(Map.of("diamond", 5));
        assertThat(settlement.refunds(Map.of("diamond", 2))).containsExactly(
                new Taking<>(Source.UNDOER_MONEY, miner, "diamond", 1, Money.of(1_000)),
                new Taking<>(Source.COLLECTOR_ITEMS, ada, "diamond", 2, Money.ZERO));
    }

    @Test
    @DisplayName("nothing is given back of a kind that was all used, and nothing is recorded for zero")
    void allUsed() {
        Settlement<String> settlement = new Settlement<>();
        settlement.add(new Taking<>(Source.GROUND, null, "diamond", 0, Money.ZERO));
        settlement.add(new Taking<>(Source.UNDOER_ITEMS, miner, "coal", 3, Money.ZERO));

        assertThat(settlement.takings()).hasSize(1);
        assertThat(settlement.refunds(Map.of("coal", 3))).isEmpty();
        assertThat(new Taking<>(Source.UNDOER_MONEY, miner, "x", 3, Money.of(250)).total()).isEqualTo(Money.of(750));
    }
}
