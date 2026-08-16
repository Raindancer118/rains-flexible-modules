package de.raindancer.modules.invsnap.rules;

import de.raindancer.modules.invsnap.model.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("which snapshots survive an addition")
class RetentionRuleTest {

    private final RetentionRule rule = new RetentionRule();
    private final UUID player = UUID.randomUUID();

    private Snapshot at(long secondsAgo) {
        return new Snapshot(player, "Someone", Instant.now().minus(secondsAgo, ChronoUnit.SECONDS),
                List.of(), List.of(), null);
    }

    @Test
    @DisplayName("nothing existing plus a fresh one keeps just the fresh one")
    void firstEverSnapshot() {
        List<Snapshot> kept = rule.applying(List.of(), at(0), 24);

        assertThat(kept).hasSize(1);
    }

    @Test
    @DisplayName("under the limit, everything is kept")
    void underTheLimitKeepsEverything() {
        List<Snapshot> existing = List.of(at(300), at(200), at(100));
        List<Snapshot> kept = rule.applying(existing, at(0), 24);

        assertThat(kept).hasSize(4);
    }

    @Test
    @DisplayName("over the limit, the oldest is dropped first")
    void overTheLimitDropsTheOldestFirst() {
        List<Snapshot> existing = List.of(at(300), at(200), at(100));
        List<Snapshot> kept = rule.applying(existing, at(0), 3);

        assertThat(kept).hasSize(3);
        assertThat(kept).extracting(Snapshot::takenAt)
                .doesNotContain(existing.get(0).takenAt());
    }

    @Test
    @DisplayName("kept snapshots come back oldest first")
    void keptOrderIsOldestFirst() {
        List<Snapshot> existing = List.of(at(300), at(100), at(200));
        List<Snapshot> kept = rule.applying(existing, null, 10);

        assertThat(kept).extracting(Snapshot::takenAt).isSorted();
    }

    @Test
    @DisplayName("a retention count below one still keeps exactly one")
    void aNonPositiveCountKeepsOne() {
        List<Snapshot> kept = rule.applying(List.of(at(300), at(200)), at(0), 0);

        assertThat(kept).hasSize(1);
    }

    @Test
    @DisplayName("adding nothing, only trims what is already there")
    void addingNothingOnlyTrims() {
        List<Snapshot> kept = rule.applying(List.of(at(300), at(200), at(100)), null, 2);

        assertThat(kept).hasSize(2);
    }
}
