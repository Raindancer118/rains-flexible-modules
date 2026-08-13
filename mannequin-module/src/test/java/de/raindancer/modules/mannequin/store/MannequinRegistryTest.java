package de.raindancer.modules.mannequin.store;

import de.raindancer.modules.mannequin.model.Leaderboard;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.TrainingSession;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MannequinRegistryTest {

    private final MannequinRegistry registry = new MannequinRegistry();

    @Nested
    @DisplayName("the stored data")
    class Ids {

        @Test
        @DisplayName("ids increase and never repeat")
        void idsIncrease() {
            assertThat(registry.nextId()).isEqualTo("MQ1");
            assertThat(registry.nextId()).isEqualTo("MQ2");
        }

        @Test
        @DisplayName("adding a mannequin with an existing high id moves the counter past it")
        void addingMovesTheCounterForward() {
            registry.put(Mannequin.freshlyPlaced("MQ5", UUID.randomUUID(), "world", 0, 64, 0));
            assertThat(registry.nextId()).isEqualTo("MQ6");
        }

        @Test
        @DisplayName("removing takes it out and forgets its session")
        void removingForgets() {
            Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
            registry.put(mannequin);
            registry.updateSession("MQ1", TrainingSession.EMPTY.hit(5.0, 100L, false));

            assertThat(registry.remove("MQ1")).isTrue();

            assertThat(registry.get("MQ1")).isEmpty();
            assertThat(registry.sessionFor("MQ1")).isEqualTo(TrainingSession.EMPTY);
        }

        @Test
        @DisplayName("removing also forgets its leaderboard")
        void removingForgetsTheLeaderboardToo() {
            registry.put(Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0));
            registry.recordLeaderboardHit("MQ1", UUID.randomUUID(), Material.STICK, null, 5.0);

            registry.remove("MQ1");

            assertThat(registry.leaderboardFor("MQ1")).isEqualTo(Leaderboard.EMPTY);
        }
    }

    @Nested
    @DisplayName("finding by owner and world")
    class Filters {

        @Test
        void ownedByFiltersCorrectly() {
            UUID owner = UUID.randomUUID();
            registry.put(Mannequin.freshlyPlaced("MQ1", owner, "world", 0, 64, 0));
            registry.put(Mannequin.freshlyPlaced("MQ2", UUID.randomUUID(), "world", 0, 64, 0));

            assertThat(registry.ownedBy(owner)).extracting(Mannequin::id).containsExactly("MQ1");
        }

        @Test
        void inWorldIsCaseInsensitive() {
            registry.put(Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "World", 0, 64, 0));

            assertThat(registry.inWorld("world")).extracting(Mannequin::id).containsExactly("MQ1");
        }
    }

    @Nested
    @DisplayName("the live entity binding")
    class LiveEntity {

        @Test
        void bindingAndUnbinding() {
            UUID entity = UUID.randomUUID();
            registry.put(Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0));
            registry.bindEntity("MQ1", entity);

            assertThat(registry.liveEntity("MQ1")).contains(entity);
            assertThat(registry.idFor(entity)).contains("MQ1");

            registry.unbindEntity("MQ1");

            assertThat(registry.liveEntity("MQ1")).isEmpty();
            assertThat(registry.idFor(entity)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the training session")
    class Session {

        @Test
        void defaultsToEmpty() {
            assertThat(registry.sessionFor("MQ1")).isEqualTo(TrainingSession.EMPTY);
        }

        @Test
        void updateAndReset() {
            registry.updateSession("MQ1", TrainingSession.EMPTY.hit(10.0, 1000L, false));
            assertThat(registry.sessionFor("MQ1").hitCount()).isEqualTo(1);

            registry.resetSession("MQ1");

            assertThat(registry.sessionFor("MQ1")).isEqualTo(TrainingSession.EMPTY);
        }

        @Test
        @DisplayName("resetting the session clears the leaderboard along with it")
        void resettingSessionClearsTheLeaderboardToo() {
            registry.recordLeaderboardHit("MQ1", UUID.randomUUID(), Material.STICK, null, 5.0);

            registry.resetSession("MQ1");

            assertThat(registry.leaderboardFor("MQ1")).isEqualTo(Leaderboard.EMPTY);
        }
    }

    @Nested
    @DisplayName("the leaderboard")
    class LeaderboardTests {

        @Test
        @DisplayName("defaults to empty for a mannequin nobody has hit")
        void defaultsToEmpty() {
            assertThat(registry.leaderboardFor("MQ1")).isEqualTo(Leaderboard.EMPTY);
        }

        @Test
        @DisplayName("records accumulate rather than replacing each other")
        void recordsAccumulate() {
            UUID player = UUID.randomUUID();
            registry.recordLeaderboardHit("MQ1", player, Material.NETHERITE_SWORD, null, 10.0);
            registry.recordLeaderboardHit("MQ1", player, Material.NETHERITE_SWORD, null, 5.0);

            assertThat(registry.leaderboardFor("MQ1").byPlayer().get(player).totalDamage())
                    .isEqualTo(15.0);
        }

        @Test
        @DisplayName("a null id, player or weapon is a no-op, not a NullPointerException")
        void missingArgumentsAreANoOp() {
            registry.recordLeaderboardHit(null, UUID.randomUUID(), Material.STICK, null, 5.0);
            registry.recordLeaderboardHit("MQ1", null, Material.STICK, null, 5.0);
            registry.recordLeaderboardHit("MQ1", UUID.randomUUID(), null, null, 5.0);

            assertThat(registry.leaderboardFor("MQ1")).isEqualTo(Leaderboard.EMPTY);
        }
    }
}
