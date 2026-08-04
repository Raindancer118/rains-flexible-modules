package de.raindancer.modules.farmworld;

import de.raindancer.modules.farmworld.model.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of the spawn platform.
 *
 * <p>Counted rather than looked at. Every mistake this catches — a stair facing into the platform, a hole
 * in the top, a corner laid as a stair pointing diagonally — is one that looks fine in code and wrong the
 * moment somebody is standing on it, and checking it on a server means generating a world each time.
 */
class PlatformTest {

    private final List<Platform.Block> blocks = Platform.blocks(2);

    @Nested
    @DisplayName("the top somebody lands on")
    class Top {

        @Test
        @DisplayName("it is three by three, with no hole in it")
        void threeByThree() {
            List<Platform.Block> top = blocks.stream().filter(Platform.Block::isTop).toList();

            assertThat(top).hasSize(9);
            assertThat(top).allSatisfy(block -> {
                assertThat(block.y()).as("the top is one flat layer").isZero();
                assertThat(Math.abs(block.x())).isLessThanOrEqualTo(Platform.TOP_RADIUS);
                assertThat(Math.abs(block.z())).isLessThanOrEqualTo(Platform.TOP_RADIUS);
            });
            assertThat(top).extracting(block -> block.x() + "," + block.z())
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("somebody is put down standing on it, not inside it")
        void theStandingSpotIsAboveTheTop() {
            // One block up. Put down at y=0 the player is inside the platform, which the server resolves by
            // pushing them somewhere — usually through it.
            assertThat(Platform.standingSpot().y()).isEqualTo(1);
            assertThat(Platform.standingSpot().x()).isZero();
            assertThat(Platform.standingSpot().z()).isZero();
        }
    }

    @Nested
    @DisplayName("the stairs around it")
    class Stairs {

        private List<Platform.Block> stairs() {
            return blocks.stream()
                    .filter(block -> block.kind() == Platform.Kind.STAIR)
                    .toList();
        }

        @Test
        @DisplayName("they make a full ring, one out and one down")
        void aFullRing() {
            // A 5x5 ring is 16 blocks. A gap in it is a corner somebody falls off.
            assertThat(stairs()).hasSize(16);
            assertThat(stairs()).allSatisfy(block -> {
                assertThat(block.y()).as("a step down from the top").isEqualTo(-1);
                assertThat(Math.max(Math.abs(block.x()), Math.abs(block.z())))
                        .isEqualTo(Platform.STAIR_RADIUS);
            });
        }

        @Test
        @DisplayName("every stair faces away from the middle")
        void theyFaceOutwards() {
            // The mistake worth catching: a ring of stairs facing inwards is a wall around the platform
            // rather than a way off it, and it reads as a bug from every side at once.
            for (Platform.Block stair : stairs()) {
                switch (stair.facing()) {
                    case NORTH -> assertThat(stair.z()).isNegative();
                    case SOUTH -> assertThat(stair.z()).isPositive();
                    case WEST -> assertThat(stair.x()).isNegative();
                    case EAST -> assertThat(stair.x()).isPositive();
                    case NONE -> assertThat(Math.abs(stair.x()))
                            .as("only a corner faces nowhere")
                            .isEqualTo(Platform.STAIR_RADIUS);
                }
            }
        }

        @Test
        @DisplayName("the four corners face nowhere, because no stair points diagonally")
        void theCornersAreNotStairs() {
            List<Platform.Block> corners = stairs().stream()
                    .filter(block -> block.facing() == Platform.Facing.NONE)
                    .toList();

            assertThat(corners).hasSize(4);
            assertThat(corners).allSatisfy(corner -> {
                assertThat(Math.abs(corner.x())).isEqualTo(Platform.STAIR_RADIUS);
                assertThat(Math.abs(corner.z())).isEqualTo(Platform.STAIR_RADIUS);
            });
        }

        @Test
        @DisplayName("the twelve that are not corners each face one way")
        void theEdgesEachFaceOneWay() {
            assertThat(stairs().stream()
                    .filter(block -> block.facing() != Platform.Facing.NONE)
                    .toList())
                    .hasSize(12);
        }
    }

    @Nested
    @DisplayName("what holds it up")
    class Base {

        @Test
        @DisplayName("there is solid ground under the whole thing, so it does not float")
        void nothingFloats() {
            List<Platform.Block> base = blocks.stream()
                    .filter(block -> block.kind() == Platform.Kind.BASE)
                    .toList();

            // Two layers of the full 5x5 footprint.
            assertThat(base).hasSize(2 * 25);
            assertThat(base).allSatisfy(block ->
                    assertThat(block.y()).as("under the stairs, never above them").isLessThan(-1));
        }

        @Test
        @DisplayName("how deep it goes is clamped, so a silly number is not a thousand blocks of stone")
        void theDepthIsClamped() {
            assertThat(Platform.blocks(0).stream()
                    .filter(block -> block.kind() == Platform.Kind.BASE).count())
                    .as("at least one layer, or the platform floats")
                    .isEqualTo(25);
            assertThat(Platform.blocks(9_000).stream()
                    .filter(block -> block.kind() == Platform.Kind.BASE).count())
                    .isEqualTo(8 * 25);
        }
    }

    @Test
    @DisplayName("no two blocks are asked for in the same place")
    void nothingOverlaps() {
        // A block placed twice is a stair overwritten by stone, and the platform quietly loses a step.
        assertThat(blocks).extracting(block -> block.x() + "," + block.y() + "," + block.z())
                .doesNotHaveDuplicates();
    }
}
