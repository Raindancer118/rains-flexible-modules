package de.raindancer.modules.rtp.store;

import de.raindancer.modules.rtp.model.PreparedSpot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the prepared spots, while the server is up")
class RtpLocationRegistryTest {

    private final RtpLocationRegistry registry = new RtpLocationRegistry();

    private static PreparedSpot at(String id, String world, int x) {
        return new PreparedSpot(id, world, x, 64, 0, Instant.now(), Set.of());
    }

    @Test
    @DisplayName("what was put in comes back out")
    void addAndCount() {
        registry.add(at("L1", "world", 0));
        registry.add(at("L2", "world", 1));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.snapshot()).extracting(PreparedSpot::id).containsExactlyInAnyOrder("L1", "L2");
    }

    @Test
    @DisplayName("a null spot is not added")
    void nullIsIgnored() {
        registry.add(null);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("removing takes it out, and says whether there was anything to take")
    void remove() {
        registry.add(at("L1", "world", 0));

        assertThat(registry.remove("L1")).isTrue();
        assertThat(registry.remove("L1")).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("clearing forgets everything, including the id sequence")
    void clear() {
        registry.add(at("L1", "world", 0));
        registry.clear();

        assertThat(registry.size()).isZero();
        assertThat(registry.nextId()).isEqualTo("L1");
    }

    @Nested
    @DisplayName("handing one out")
    class AvailableFor {

        @Test
        @DisplayName("a spot nobody has been sent to is available to anybody")
        void freshSpotIsAvailable() {
            UUID player = UUID.randomUUID();
            registry.add(at("L1", "world", 0));

            assertThat(registry.availableFor(player, "world")).extracting(PreparedSpot::id)
                    .containsExactly("L1");
        }

        @Test
        @DisplayName("a spot this player has already used is not offered to them again")
        void usedIsNotOfferedAgain() {
            UUID player = UUID.randomUUID();
            registry.add(at("L1", "world", 0));
            registry.markUsed("L1", player);

            assertThat(registry.availableFor(player, "world")).isEmpty();
        }

        @Test
        @DisplayName("a spot used by somebody else is still offered")
        void usedBySomeoneElseIsStillOffered() {
            UUID used = UUID.randomUUID();
            UUID asking = UUID.randomUUID();
            registry.add(at("L1", "world", 0));
            registry.markUsed("L1", used);

            assertThat(registry.availableFor(asking, "world")).extracting(PreparedSpot::id)
                    .containsExactly("L1");
        }

        @Test
        @DisplayName("only the world asked for is offered")
        void onlyThatWorld() {
            UUID player = UUID.randomUUID();
            registry.add(at("L1", "world", 0));
            registry.add(at("L2", "world_nether", 0));

            List<PreparedSpot> found = registry.availableFor(player, "world");
            assertThat(found).extracting(PreparedSpot::id).containsExactly("L1");
        }

        @Test
        @DisplayName("a world is matched case-insensitively")
        void worldIsCaseInsensitive() {
            UUID player = UUID.randomUUID();
            registry.add(at("L1", "World", 0));

            assertThat(registry.availableFor(player, "world")).hasSize(1);
        }

        @Test
        @DisplayName("marking a spot that no longer exists does nothing")
        void markingAGoneSpotDoesNothing() {
            registry.markUsed("L1", UUID.randomUUID());
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("a null player or world asks for nothing rather than everything")
        void nullAsksForNothing() {
            registry.add(at("L1", "world", 0));

            assertThat(registry.availableFor(null, "world")).isEmpty();
            assertThat(registry.availableFor(UUID.randomUUID(), null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the id sequence")
    class Ids {

        @Test
        @DisplayName("counts up, starting from one")
        void countsUp() {
            assertThat(registry.nextId()).isEqualTo("L1");
            assertThat(registry.nextId()).isEqualTo("L2");
        }

        @Test
        @DisplayName("loading spots from disk moves the counter past the highest one seen")
        void loadingMovesTheCounter() {
            registry.add(at("L7", "world", 0));
            assertThat(registry.nextId()).isEqualTo("L8");
        }

        @Test
        @DisplayName("an id from somewhere else does not move the counter")
        void foreignIdDoesNotMoveTheCounter() {
            registry.add(at("imported-1", "world", 0));
            assertThat(registry.nextId()).isEqualTo("L1");
        }
    }
}
