package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place the shape of a dig, and not merely how much of it was ore, gets judged.
 *
 * <h2>The two shapes this exists to tell apart</h2>
 * A player who mines a straight line of stone from far away and comes out the other side standing on
 * a diamond went almost the whole distance in a straight line — high directness. A player carving out
 * an ordinary room or branch mine covers a lot of ground for very little net distance, and happens to
 * break into a vein along the way — low directness, however much ore that vein turns out to hold. Both
 * scenarios below are written exactly that literally, on purpose: the numbers should read as obviously
 * right to somebody who has never seen this class before.
 */
class MiningTrailTest {

    private static MinedBlock stone(int x, int y, int z) {
        return new MinedBlock("world", x, y, z, "STONE");
    }

    private static MinedBlock diamond(int x, int y, int z) {
        return new MinedBlock("world", x, y, z, "DIAMOND_ORE");
    }

    @Nested
    @DisplayName("what counts as watched ore")
    class Watching {

        @Test
        @DisplayName("matches the material list case-insensitively")
        void caseInsensitive() {
            MiningTrail trail = new MiningTrail(100);
            trail.record(new MinedBlock("world", 0, 64, 0, "stone"));
            trail.record(new MinedBlock("world", 1, 64, 0, "diamond_ore"));

            assertThat(trail.oreApproaches(List.of("DIAMOND_ORE"))).hasSize(1);
        }

        @Test
        @DisplayName("a material nobody is watching for is invisible to this, whatever it is")
        void ignoresUnwatchedMaterial() {
            MiningTrail trail = new MiningTrail(100);
            for (int x = 0; x < 10; x++) {
                trail.record(diamond(x, 64, 0));
            }

            assertThat(trail.oreApproaches(List.of("ANCIENT_DEBRIS"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("the shape of the approach")
    class Approach {

        @Test
        @DisplayName("a straight tunnel dug directly at the ore reads highly direct")
        void straightLineReadsDirect() {
            MiningTrail trail = new MiningTrail(100);
            for (int x = 0; x < 10; x++) {
                trail.record(stone(x, 64, 0));
            }
            trail.record(diamond(10, 64, 0));

            ApproachReading reading = trail.oreApproaches(List.of("DIAMOND_ORE")).getFirst();

            assertThat(reading.pathLength()).isEqualTo(10);
            assertThat(reading.directnessPercent())
                    .as("ten blocks mined in a dead straight line covering ten blocks of distance "
                            + "is as direct as digging gets")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("a winding path that doubles back reads far less direct for the same ore")
        void windingPathReadsLessDirect() {
            MiningTrail trail = new MiningTrail(100);
            // Six blocks east along one row, then three blocks back west along the row beside it —
            // nine blocks mined in total, exactly like an ordinary branch mine, ending up only a
            // couple of blocks in a straight line from where the dig actually started.
            for (int x = 0; x <= 5; x++) {
                trail.record(stone(x, 64, 0));
            }
            trail.record(stone(5, 64, 1));
            trail.record(stone(4, 64, 1));
            trail.record(stone(3, 64, 1));
            trail.record(diamond(2, 64, 1));

            ApproachReading reading = trail.oreApproaches(List.of("DIAMOND_ORE")).getFirst();

            assertThat(reading.pathLength()).isEqualTo(9);
            assertThat(reading.directnessPercent())
                    .as("far below the straight tunnel's score, for a path that did just as much "
                            + "digging to end up much closer to where it started")
                    .isLessThan(30);
        }

        @Test
        @DisplayName("an ore block with nothing remembered before it has no reading at all")
        void noContextMeansNoReading() {
            MiningTrail trail = new MiningTrail(100);
            trail.record(diamond(0, 64, 0));

            assertThat(trail.oreApproaches(List.of("DIAMOND_ORE")))
                    .as("there is no 'before' to compare this to yet, and a reading built on nothing "
                            + "is worse than no reading at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("a jump far larger than a single dig ends the approach right there")
        void aBigJumpEndsTheApproach() {
            MiningTrail trail = new MiningTrail(100);
            // Mining somewhere else entirely...
            trail.record(stone(-500, 64, -500));
            // ...then walking over (no blocks mined in between) and digging a short, genuine
            // approach to this ore. Only the second stretch should ever count.
            trail.record(stone(8, 64, 0));
            trail.record(stone(9, 64, 0));
            trail.record(diamond(10, 64, 0));

            ApproachReading reading = trail.oreApproaches(List.of("DIAMOND_ORE")).getFirst();

            assertThat(reading.pathLength())
                    .as("the unrelated block five hundred away must not be counted as part of this dig")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a different world entirely is never counted as the same dig")
        void aDifferentWorldEndsTheApproach() {
            MiningTrail trail = new MiningTrail(100);
            trail.record(new MinedBlock("nether", 9, 64, 0, "STONE"));
            trail.record(diamond(9, 64, 1));

            assertThat(trail.oreApproaches(List.of("DIAMOND_ORE")))
                    .as("adjacent coordinates in two different worlds are not adjacent at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("the lookback is bounded, so one very long tunnel costs no more than a short one")
        void lookbackIsBounded() {
            MiningTrail trail = new MiningTrail(1000);
            for (int x = 0; x < 200; x++) {
                trail.record(stone(x, 64, 0));
            }
            trail.record(diamond(200, 64, 0));

            ApproachReading reading = trail.oreApproaches(List.of("DIAMOND_ORE")).getFirst();

            assertThat(reading.pathLength())
                    .as("bounded at the lookback limit, well below the two hundred blocks actually mined")
                    .isEqualTo(40);
        }
    }

    @Nested
    @DisplayName("holding only so much")
    class Capacity {

        @Test
        @DisplayName("the oldest blocks are forgotten once the trail is full")
        void dropsTheOldest() {
            MiningTrail trail = new MiningTrail(3);
            trail.record(diamond(0, 64, 0));
            trail.record(stone(1, 64, 0));
            trail.record(stone(2, 64, 0));
            trail.record(stone(3, 64, 0));

            assertThat(trail.oreApproaches(List.of("DIAMOND_ORE")))
                    .as("the diamond fell out of the trail exactly as an old boolean would out of "
                            + "MiningWindow")
                    .isEmpty();
        }

        @Test
        @DisplayName("a capacity of zero or less still holds at least one block")
        void capacityIsAtLeastOne() {
            MiningTrail trail = new MiningTrail(0);
            trail.record(stone(0, 64, 0));
            trail.record(diamond(1, 64, 0));

            // Reaching here without an exception, on a trail that could otherwise hold nothing, is
            // most of the assertion — the rest is that the one block it does hold is still usable.
            assertThat(trail.oreApproaches(List.of("DIAMOND_ORE"))).isEmpty();
        }
    }
}
