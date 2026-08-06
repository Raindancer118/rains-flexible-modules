package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.ArenaRing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the starting ring is the shape it promises to be.
 *
 * <p>This is the one part of arena generation that is arithmetic rather than block placing, and in the plugin
 * this was ported from it was the part nothing checked — it lived inside the preflight runner between a terrain
 * flatten and a chat message, reachable only by running {@code /init} on a real server and looking at the
 * result. Which is how it came to size the ring by the arc between platforms rather than the straight line, and
 * quietly hand out a smaller gap than every owner had configured.
 */
class ArenaRingTest {

    /** What the shipped defaults are: three-wide platforms with two blocks between them. */
    private static final int WIDTH = 3;
    private static final int GAP = 2;

    @Nested
    @DisplayName("the gap an owner configures")
    class TheGap {

        @Test
        @DisplayName("the gap is honoured for every tribute count a tournament might have")
        void theGapIsAlwaysHonoured() {
            // The regression this class exists for. Checked across the whole range rather than at one count,
            // because the arc-versus-chord error shrinks as the count grows: it is worst at three and
            // invisible at twenty-four, so a test written at a large count would have passed against the
            // original code.
            for (int tributes = 1; tributes <= 64; tributes++) {
                ArenaRing ring = ArenaRing.forTributes(tributes, WIDTH, GAP);
                assertThat(ring.gapIsHonoured(1e-9))
                        .as("%d tributes: asked for %d blocks between platforms, got %.3f",
                                tributes, GAP, ring.actualGap())
                        .isTrue();
            }
        }

        /** What the ring's radius would have been under the original arc-based formula. */
        private static double radiusTheOldWay(int tributes, int width, int gap) {
            return Math.max(ArenaRing.SMALLEST_RADIUS, tributes * (double) (width + gap) / (2 * Math.PI));
        }

        /** The gap a ring of that radius really leaves. */
        private static double gapAtRadius(double radius, int tributes, int width) {
            return 2 * radius * Math.sin(Math.PI / tributes) - width;
        }

        @Test
        @DisplayName("the arc-based formula really did fall short — at the counts a tournament actually uses")
        void theOldFormulaIsDemonstrablyWrong() {
            // The mutation check, written out rather than trusted. Without it the test above is a rule that
            // might always have passed, and a test that cannot fail against the broken code is not evidence.
            //
            // The instructive part is *where* it fails, because it is not where the arithmetic suggests. The
            // arc overstates the chord most at small counts, so three platforms look like the worst case — but
            // three are rescued by SMALLEST_RADIUS, which sets the radius long before the spacing does. Both
            // formulas clamp there, so a test written at three would have passed against the original code and
            // proved nothing. That was this test's first draft.
            //
            // The shortfall shows up just above where the floor stops binding, and how far up depends on the
            // gap that is configured.
            record Case(int tributes, int width, int gap) {
            }
            for (Case broken : List.of(
                    // The shipped defaults: every tournament of thirteen or more was short.
                    new Case(13, WIDTH, GAP), new Case(20, WIDTH, GAP), new Case(24, WIDTH, GAP),
                    // A generous gap moves the problem down to five tributes, and makes it nearly a block.
                    new Case(5, 3, 10), new Case(8, 3, 10),
                    // And a wider platform with a middling gap: from six upwards.
                    new Case(6, 5, 6))) {

                double oldGap = gapAtRadius(
                        radiusTheOldWay(broken.tributes(), broken.width(), broken.gap()),
                        broken.tributes(), broken.width());

                assertThat(oldGap)
                        .as("%d tributes, %d wide, %d apart: the arc formula was supposed to be short here",
                                broken.tributes(), broken.width(), broken.gap())
                        .isLessThan(broken.gap());

                assertThat(ArenaRing.forTributes(broken.tributes(), broken.width(), broken.gap()).actualGap())
                        .as("%d tributes, %d wide, %d apart: the chord formula has to deliver it",
                                broken.tributes(), broken.width(), broken.gap())
                        .isGreaterThanOrEqualTo(broken.gap() - 1e-9);
            }
        }

        @Test
        @DisplayName("small rounds were never broken, because the radius floor was already saving them")
        void theFloorWasHidingItAtSmallCounts() {
            // Kept as its own case because it is the reason the defect survived: at these counts both formulas
            // give the same ring, so anybody checking a small round by eye saw a correct arena.
            for (int tributes : new int[]{2, 3, 4, 6, 8}) {
                assertThat(ArenaRing.forTributes(tributes, WIDTH, GAP).radius())
                        .as("%d tributes: the floor, not the spacing, sets this radius", tributes)
                        .isEqualTo(ArenaRing.SMALLEST_RADIUS);
            }
        }

        @Test
        @DisplayName("a wider gap makes a bigger ring")
        void aWiderGapPushesThemApart() {
            double tight = ArenaRing.forTributes(24, WIDTH, 1).radius();
            double roomy = ArenaRing.forTributes(24, WIDTH, 10).radius();

            assertThat(roomy).isGreaterThan(tight);
        }

        @Test
        @DisplayName("a single platform has no neighbour, and no gap to fail")
        void oneIsNotACircle() {
            ArenaRing ring = ArenaRing.forTributes(1, WIDTH, GAP);

            assertThat(ring.spots()).hasSize(1);
            // Infinite rather than zero: zero reads as "on top of each other" and would fail every check,
            // about a ring that cannot possibly be too crowded.
            assertThat(ring.neighbourDistance()).isInfinite();
            assertThat(ring.gapIsHonoured(0)).isTrue();
        }

        @Test
        @DisplayName("two platforms sit opposite each other")
        void twoAreOpposite() {
            ArenaRing ring = ArenaRing.forTributes(2, WIDTH, GAP);
            List<ArenaRing.Spot> spots = ring.spots();

            assertThat(spots).hasSize(2);
            // The whole diameter is between them.
            assertThat(ring.neighbourDistance()).isCloseTo(2 * ring.radius(),
                    org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("the ring itself")
    class Geometry {

        @Test
        @DisplayName("there is one platform per tribute, all at the same distance from the middle")
        void everyPlatformIsOnTheCircle() {
            ArenaRing ring = ArenaRing.forTributes(12, WIDTH, GAP);

            assertThat(ring.spots()).hasSize(12);
            for (ArenaRing.Spot spot : ring.spots()) {
                double distance = Math.hypot(spot.x(), spot.z());
                assertThat(distance)
                        .as("a platform off the circle is one closer to its neighbour than the rest")
                        .isCloseTo(ring.radius(), org.assertj.core.data.Offset.offset(1e-9));
            }
        }

        @Test
        @DisplayName("the platforms are evenly spaced")
        void spacingIsEven() {
            // Uneven spacing means one pair is closer than the configured gap while the average looks right —
            // and the pair that is too close is the one where somebody reaches a neighbour before the bell.
            ArenaRing ring = ArenaRing.forTributes(8, WIDTH, GAP);
            List<ArenaRing.Spot> spots = ring.spots();

            for (int i = 0; i < spots.size(); i++) {
                ArenaRing.Spot here = spots.get(i);
                ArenaRing.Spot next = spots.get((i + 1) % spots.size());
                double distance = Math.hypot(next.x() - here.x(), next.z() - here.z());
                assertThat(distance).isCloseTo(ring.neighbourDistance(),
                        org.assertj.core.data.Offset.offset(1e-9));
            }
        }

        @Test
        @DisplayName("every platform faces the middle")
        void everybodyLooksInward() {
            // The assertion that stops somebody "fixing" the +90 in the yaw. Minecraft's yaw runs from south
            // and a yaw of y looks along (-sin y, cos y); checked by walking one block along that heading and
            // confirming it gets closer to the centre.
            ArenaRing ring = ArenaRing.forTributes(16, WIDTH, GAP);

            for (ArenaRing.Spot spot : ring.spots()) {
                double yaw = Math.toRadians(spot.yaw());
                double lookX = -Math.sin(yaw);
                double lookZ = Math.cos(yaw);

                double before = Math.hypot(spot.x(), spot.z());
                double after = Math.hypot(spot.x() + lookX, spot.z() + lookZ);

                assertThat(after)
                        .as("a platform at (%.2f, %.2f) with yaw %.1f looks away from the middle",
                                spot.x(), spot.z(), spot.yaw())
                        .isLessThan(before);
            }
        }

        @Test
        @DisplayName("no ring is smaller than the cornucopia it surrounds")
        void thereIsAFloor() {
            // Two tributes on narrow platforms would otherwise be given a radius of a couple of blocks, which
            // puts them inside the middle build rather than around it.
            assertThat(ArenaRing.forTributes(2, 1, 0).radius())
                    .isGreaterThanOrEqualTo(ArenaRing.SMALLEST_RADIUS);
        }

        @Test
        @DisplayName("nonsensical inputs make a ring rather than an exception")
        void nonsenseIsClamped() {
            // These arrive from a config file by way of a settings record. A zero platform count reaches the
            // angle calculation as a division by zero, and a negative width as a ring that curls inside out.
            assertThat(ArenaRing.forTributes(0, WIDTH, GAP).spots()).hasSize(1);
            assertThat(ArenaRing.forTributes(-5, WIDTH, GAP).spots()).hasSize(1);
            assertThat(ArenaRing.forTributes(4, -3, -3).radius())
                    .isGreaterThanOrEqualTo(ArenaRing.SMALLEST_RADIUS);
        }
    }

    @Nested
    @DisplayName("the ground under it")
    class Ground {

        @Test
        @DisplayName("the flattened ground reaches past the platforms, not just to their centres")
        void theGroundCoversTheWholePlatform() {
            // The radius locates a platform's *centre*, so half of every platform sticks out beyond it.
            // Flattened only to the radius, each one ends up half buried — which showed up in the original as
            // platforms with a wall along their outer edge.
            ArenaRing ring = ArenaRing.forTributes(12, WIDTH, GAP);

            assertThat(ring.groundRadius(0))
                    .as("the ground has to reach the outer edge of a platform")
                    .isGreaterThanOrEqualTo((int) Math.ceil(ring.radius() + WIDTH / 2.0));
        }

        @Test
        @DisplayName("the configured extra margin is added on top")
        void theMarginIsAdded() {
            ArenaRing ring = ArenaRing.forTributes(12, WIDTH, GAP);

            assertThat(ring.groundRadius(20) - ring.groundRadius(0)).isEqualTo(20);
            assertThat(ring.groundRadius(-5))
                    .as("a negative margin must not shrink the ground under the platforms")
                    .isEqualTo(ring.groundRadius(0));
        }
    }
}
