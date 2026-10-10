package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MiningLedgerTest {

    @Test
    @DisplayName("finds survive digging in another world — the counts they explain do too")
    void findsSurviveAWorldChange() {
        MiningLedger ledger = new MiningLedger(0);
        ledger.dug("world", 1, -50, 1, OreKind.DIAMOND.ordinal());
        ledger.dug("world", 2, -50, 2, MiningLedger.BAIT);

        ledger.dug("lobby", 0, 64, 0, MiningLedger.ROCK);

        assertThat(ledger.finds()).extracting(MiningLedger.Find::world, MiningLedger.Find::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("world", OreKind.DIAMOND.ordinal()),
                        org.assertj.core.groups.Tuple.tuple("world", MiningLedger.BAIT));
    }

    @Test
    @DisplayName("finds survive thousands of plain rock blocks dug after them")
    void findsSurviveLongDigging() {
        MiningLedger ledger = new MiningLedger(0);
        ledger.dug("world", 1, -50, 1, OreKind.DIAMOND.ordinal());
        for (int i = 0; i < MiningLedger.TRAIL * 2; i++) {
            ledger.dug("world", i, -50, 0, MiningLedger.ROCK);
        }

        assertThat(ledger.finds()).hasSize(1);
        assertThat(ledger.trail()).hasSize(MiningLedger.TRAIL);
    }

    @Test
    @DisplayName("finds are capped, oldest dropped first")
    void findsAreCapped() {
        MiningLedger ledger = new MiningLedger(0);
        for (int i = 0; i < MiningLedger.FINDS + 5; i++) {
            ledger.dug("world", i, -50, 0, MiningLedger.BAIT);
        }

        assertThat(ledger.finds()).hasSize(MiningLedger.FINDS);
        assertThat(ledger.finds().get(0).x()).isEqualTo(5);
    }

    @Test
    @DisplayName("an old save without finds gets them from its trail")
    void oldSaveFindsComeFromTrail() {
        MiningLedger ledger = new MiningLedger(0);
        ledger.restore(Map.of(), Map.of(), 0, 0, 0, 0, 0, "world",
                List.of(new int[]{1, 2, 3, MiningLedger.ROCK}, new int[]{4, 5, 6, MiningLedger.BAIT}), null);

        assertThat(ledger.finds()).containsExactly(new MiningLedger.Find("world", 4, 5, 6, MiningLedger.BAIT));
    }
}
