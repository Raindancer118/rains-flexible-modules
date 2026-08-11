package de.raindancer.modules.chained.rules;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pure boundary math — no server anywhere near it, following {@code Proximity}'s own test pattern.
 */
class ChainDistanceRuleTest {

    private final ChainDistanceRule rule = new ChainDistanceRule();
    private final World world = mock(World.class);

    @Nested
    @DisplayName("moving while within the limit")
    class WithinLimit {

        @Test
        @DisplayName("moving further while still inside the limit is allowed")
        void allowedInside() {
            Location partner = new Location(world, 0, 64, 0);
            Location from = new Location(world, 5, 64, 0);
            Location to = new Location(world, 10, 64, 0);   // still under 32

            assertThat(rule.wouldExceed(from, to, partner, 32)).isFalse();
        }
    }

    @Nested
    @DisplayName("moving past the limit")
    class PastLimit {

        @Test
        @DisplayName("a move that would push the distance past the limit is blocked")
        void blockedPastLimit() {
            Location partner = new Location(world, 0, 64, 0);
            Location from = new Location(world, 30, 64, 0);
            Location to = new Location(world, 35, 64, 0);   // past 32, and further than before

            assertThat(rule.wouldExceed(from, to, partner, 32)).isTrue();
        }

        @Test
        @DisplayName("moving closer while already past the limit is still allowed")
        void walkingBackIsAllowed() {
            // Already forced apart — has to be able to walk back toward the partner.
            Location partner = new Location(world, 0, 64, 0);
            Location from = new Location(world, 50, 64, 0);
            Location to = new Location(world, 45, 64, 0);   // still over 32, but closer than before

            assertThat(rule.wouldExceed(from, to, partner, 32)).isFalse();
        }

        @Test
        @DisplayName("staying at exactly the same distance while past the limit is allowed")
        void sidestepAtTheSameDistanceIsAllowed() {
            Location partner = new Location(world, 0, 64, 0);
            Location from = new Location(world, 50, 64, 0);
            Location to = new Location(world, 0, 64, 50);   // same distance (50), different direction

            assertThat(rule.wouldExceed(from, to, partner, 32)).isFalse();
        }

        @Test
        @DisplayName("a widened limit is read fresh: no false positive when it is not tight any more")
        void widenedLimitIsNotAFalsePositive() {
            // An admin who widened max distance mid-run must not have the old, tighter limit still
            // refuse a move that is now well within the new one — even though the move is "further"
            // than a moment ago.
            Location partner = new Location(world, 0, 64, 0);
            Location from = new Location(world, 10, 64, 0);
            Location to = new Location(world, 20, 64, 0);

            assertThat(rule.wouldExceed(from, to, partner, 100)).isFalse();
        }
    }

    @Nested
    @DisplayName("height does not count as separating")
    class Height {

        @Test
        @DisplayName("falling or jumping straight down/up does not trip the wall")
        void verticalMovementIsIgnored() {
            Location partner = new Location(world, 0, 64, 0);
            Location from = new Location(world, 0, 64, 0);
            Location to = new Location(world, 0, 0, 0);   // straight down, sixty-four blocks

            assertThat(rule.wouldExceed(from, to, partner, 32)).isFalse();
        }
    }
}
