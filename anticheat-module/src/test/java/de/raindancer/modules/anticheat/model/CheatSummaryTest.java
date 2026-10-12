package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheatSummaryTest {

    private static final long NOW = 10_000_000;

    private static Evidence flag(long at, CheckType check, double level) {
        return new Evidence(at, check.key(), level, "detail", "world", 0, 64, 0, 40, 20);
    }

    @Test
    @DisplayName("online: the live levels, the worst check, the last flag and the replays")
    void online() {
        PlayerTrack track = new PlayerTrack(UUID.randomUUID(), "Cheater", () -> NOW);
        track.violations().add(CheckType.FLY, 8);
        track.violations().add(CheckType.SPEED, 3);
        var summary = CheatSummary.of(track, List.of(flag(NOW - 1000, CheckType.SPEED, 3), flag(NOW - 5000, CheckType.FLY, 8)), 2, NOW);
        assertThat(summary).hasValueSatisfying(it -> {
            assertThat(it.level()).isEqualTo(11);
            assertThat(it.worstCheck()).isEqualTo(CheckType.FLY.title());
            assertThat(it.flaggedChecks()).isEqualTo(2);
            assertThat(it.lastCheck()).isEqualTo(CheckType.SPEED.title());
            assertThat(it.lastAtMillis()).isEqualTo(NOW - 1000);
            assertThat(it.replays()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("offline: what the last week of evidence says, each check at its highest")
    void offline() {
        var summary = CheatSummary.of(null, List.of(flag(NOW - 1000, CheckType.REACH, 4), flag(NOW - 2000, CheckType.REACH, 6),
                flag(NOW - 30L * 86_400_000, CheckType.FLY, 40)), 0, NOW);
        assertThat(summary).hasValueSatisfying(it -> {
            assertThat(it.level()).isEqualTo(6);
            assertThat(it.worstCheck()).isEqualTo(CheckType.REACH.title());
            assertThat(it.flaggedChecks()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("nothing at all is no record")
    void clean() {
        assertThat(CheatSummary.of(null, List.of(), 0, NOW)).isEmpty();
    }
}
