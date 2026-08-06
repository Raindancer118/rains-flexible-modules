package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.visual.BarrierRing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the ring holding tributes on their platforms is the right shape.
 *
 * <p>Only the geometry is checked here, because only the geometry can be: placing blocks needs a world. But the
 * geometry is where the one silent failure lives — a ring that also fills the middle stops the levitation that
 * puts tributes on their platforms, and the symptom is a round where nobody arrives and nothing in the log says
 * why. The two material rules that matter as much are stated on the methods and are visible in four lines of
 * code each; this is the part that is not obvious by reading.
 */
class BarrierRingTest {

    @Test
    @DisplayName("the ring is the eight neighbours and never the middle")
    void theMiddleIsLeftOpen() {
        assertThat(BarrierRing.offsets())
                .as("eight neighbours, no more and no fewer")
                .hasSize(8);

        assertThat(BarrierRing.offsets())
                .as("the middle is the way up — a barrier there stops the levitation that lifts a tribute "
                        + "onto their platform, and the round simply never starts")
                .doesNotContain(new BarrierRing.Offset(0, 0));
    }

    @Test
    @DisplayName("the ring completely encloses the middle")
    void thereIsNoGapToWalkThrough() {
        // Eight distinct neighbours is not the same claim as "no way out": seven of the eight plus a duplicate
        // would also be eight. Checked by asserting every neighbouring column is present.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                assertThat(BarrierRing.offsets())
                        .as("a gap at (%d, %d) is the one square a tribute walks out through", dx, dz)
                        .contains(new BarrierRing.Offset(dx, dz));
            }
        }
    }

    @Test
    @DisplayName("nothing is listed twice, so the count is the number of blocks")
    void theOffsetsAreDistinct() {
        // place() and remove() return how many blocks they touched, and a duplicated offset would inflate that
        // count — which is the only signal that a ring failed to go up at all.
        assertThat(BarrierRing.offsets()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the ring sits at head height")
    void headHeightNotFootHeight() {
        // At foot height it is a wall to jump over; two blocks up it is a ceiling to walk under. One block up
        // is the only value that holds somebody in place.
        assertThat(BarrierRing.HEIGHT_ABOVE_FEET).isEqualTo(1);
    }
}
