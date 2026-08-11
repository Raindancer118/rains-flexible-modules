package de.raindancer.modules.rtp.model;

import de.raindancer.core.world.safety.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a prepared spot")
class PreparedSpotTest {

    private static PreparedSpot spot() {
        return new PreparedSpot("L1", "world", 10, 64, -20, Instant.now(), Set.of());
    }

    @Test
    @DisplayName("a null used-by set is nobody, not a crash")
    void nullUsedByIsNobody() {
        PreparedSpot spot = new PreparedSpot("L1", "world", 0, 64, 0, Instant.now(), null);
        assertThat(spot.usedBy()).isEmpty();
    }

    @Test
    @DisplayName("turns into Core's own coordinate type, untouched")
    void becomesASpot() {
        PreparedSpot prepared = spot();
        assertThat(prepared.spot()).isEqualTo(new Spot("world", 10, 64, -20));
    }

    @Nested
    @DisplayName("who has been sent here")
    class UsedBy {

        @Test
        @DisplayName("nobody, to start with")
        void nobodyYet() {
            UUID somebody = UUID.randomUUID();
            assertThat(spot().usedBy(somebody)).isFalse();
        }

        @Test
        @DisplayName("marking a player used is the only thing it changes")
        void markingChangesOnlyThat() {
            UUID player = UUID.randomUUID();
            PreparedSpot before = spot();
            PreparedSpot after = before.markUsedBy(player);

            assertThat(after.usedBy(player)).isTrue();
            assertThat(after.id()).isEqualTo(before.id());
            assertThat(after.x()).isEqualTo(before.x());
            assertThat(after.y()).isEqualTo(before.y());
            assertThat(after.z()).isEqualTo(before.z());
        }

        @Test
        @DisplayName("marking somebody already marked changes nothing")
        void markingTwiceIsTheSameAsOnce() {
            UUID player = UUID.randomUUID();
            PreparedSpot once = spot().markUsedBy(player);
            PreparedSpot twice = once.markUsedBy(player);

            assertThat(twice.usedBy()).isEqualTo(once.usedBy());
        }

        @Test
        @DisplayName("marking a null player is refused rather than crashing")
        void nullPlayerIsRefused() {
            PreparedSpot before = spot();
            assertThat(before.markUsedBy(null)).isSameAs(before);
        }

        @Test
        @DisplayName("one player being marked does not mark another")
        void onlyThatPlayer() {
            UUID marked = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            PreparedSpot after = spot().markUsedBy(marked);

            assertThat(after.usedBy(marked)).isTrue();
            assertThat(after.usedBy(other)).isFalse();
        }
    }
}
