package de.raindancer.modules.xaeromap.rules;

import de.raindancer.modules.xaeromap.Facts;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a chunk shared by two claims goes to one of them, predictably.
 *
 * <p>A map draws whole chunks and claims here are arbitrary polygons, so this decision has to be made
 * somewhere. Made badly it produces a map that changes its mind between refreshes — the same server,
 * the same claims, a different picture — which is worse than no map.
 */
class ChunkCoverageRuleTest {

    private static final UUID ONE = UUID.randomUUID();
    private static final UUID TWO = UUID.randomUUID();

    @Test
    @DisplayName("a claim that covers a chunk gets it")
    void theObviousCase() {
        ClaimFacts claim = Facts.claim("Home", ONE, Facts.OVERWORLD, Facts.chunk(0, 0));

        Map<Long, UUID> chunks = new ChunkCoverageRule(1).chunksOf(List.of(claim));

        assertThat(chunks).containsExactly(Map.entry(Facts.chunk(0, 0), claim.id()));
    }

    @Test
    @DisplayName("the claim covering more of a shared chunk keeps it")
    void themostOfItWins() {
        ClaimFacts bigger = Facts.partial("Bigger", ONE, Facts.chunk(0, 0), 200);
        ClaimFacts smaller = Facts.partial("Smaller", TWO, Facts.chunk(0, 0), 56);

        assertThat(new ChunkCoverageRule(1).chunksOf(List.of(smaller, bigger)))
                .containsEntry(Facts.chunk(0, 0), bigger.id());
        assertThat(new ChunkCoverageRule(1).chunksOf(List.of(bigger, smaller)))
                .as("the answer cannot depend on which claim was looked at first — the registry's "
                        + "order is a hash order and changes between restarts")
                .containsEntry(Facts.chunk(0, 0), bigger.id());
    }

    @Test
    @DisplayName("split a chunk exactly, the older claim keeps it")
    void ageBreaksTheTie() {
        ClaimFacts older = Facts.partial("Older", ONE, Facts.chunk(0, 0), 128, 1_000L);
        ClaimFacts newer = Facts.partial("Newer", TWO, Facts.chunk(0, 0), 128, 2_000L);

        assertThat(new ChunkCoverageRule(1).chunksOf(List.of(older, newer)))
                .as("a new neighbour must not repaint an established claim")
                .containsEntry(Facts.chunk(0, 0), older.id());
        assertThat(new ChunkCoverageRule(1).chunksOf(List.of(newer, older)))
                .containsEntry(Facts.chunk(0, 0), older.id());
    }

    @Test
    @DisplayName("two claims made in the same millisecond still produce one stable answer")
    void thereIsAlwaysALastResort() {
        ClaimFacts one = Facts.partial("One", ONE, Facts.chunk(0, 0), 128, 5L);
        ClaimFacts two = Facts.partial("Two", TWO, Facts.chunk(0, 0), 128, 5L);

        UUID first = new ChunkCoverageRule(1).chunksOf(List.of(one, two)).get(Facts.chunk(0, 0));
        UUID second = new ChunkCoverageRule(1).chunksOf(List.of(two, one)).get(Facts.chunk(0, 0));

        assertThat(first)
                .as("two syncs of an unchanged server disagreeing is a map that flickers")
                .isEqualTo(second);
    }

    @Test
    @DisplayName("a claim barely clipping a chunk does not paint the whole of it")
    void theThresholdKeepsSliversOff() {
        // Four columns of 256 is a little over one and a half percent.
        ClaimFacts sliver = Facts.partial("Sliver", ONE, Facts.chunk(0, 0), 4);

        assertThat(new ChunkCoverageRule(1).chunksOf(List.of(sliver)))
                .as("at one percent nothing visible is left out")
                .isNotEmpty();
        assertThat(new ChunkCoverageRule(25).chunksOf(List.of(sliver)))
                .as("an owner who set a quarter meant it")
                .isEmpty();
    }

    @Test
    @DisplayName("a claim covering nothing of a chunk never wins it")
    void zeroIsNeverEnough() {
        ClaimFacts nothing = Facts.partial("Nothing", ONE, Facts.chunk(0, 0), 0);

        assertThat(new ChunkCoverageRule(1).chunksOf(List.of(nothing))).isEmpty();
        assertThat(new ChunkCoverageRule(1).enough(0)).isFalse();
    }

    @Test
    @DisplayName("the threshold is clamped, so a nonsense config still draws a map")
    void theThresholdIsClamped() {
        assertThat(new ChunkCoverageRule(-5).minimumPercent()).isEqualTo(1);
        assertThat(new ChunkCoverageRule(5000).minimumPercent()).isEqualTo(100);
        assertThat(new ChunkCoverageRule(100).enough(256))
                .as("a hundred percent has to still accept a full chunk, or it draws nothing at all")
                .isTrue();
    }
}
