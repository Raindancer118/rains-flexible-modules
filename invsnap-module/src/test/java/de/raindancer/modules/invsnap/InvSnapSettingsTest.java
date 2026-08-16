package de.raindancer.modules.invsnap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Every default, spelled out by name — see {@code MannequinSettingsTest} for why this is not trusted. */
class InvSnapSettingsTest {

    private final InvSnapSettings defaults = InvSnapSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("every five minutes, twenty-four kept")
        void eachOneByName() {
            assertThat(defaults.snapshotIntervalSeconds()).isEqualTo(300);
            assertThat(defaults.retentionCount()).isEqualTo(24);
            assertThat(defaults.snapshotInterval()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    @DisplayName("clamping")
    class Clamping {

        @Test
        @DisplayName("the interval is clamped into its declared range")
        void intervalIsClamped() {
            assertThat(defaults.withSnapshotIntervalSeconds(0).snapshotInterval())
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(defaults.withSnapshotIntervalSeconds(999_999).snapshotInterval())
                    .isEqualTo(Duration.ofSeconds(86_400));
            assertThat(defaults.withSnapshotIntervalSeconds(120).snapshotInterval())
                    .isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("the retention count is clamped, never below one")
        void retentionIsClamped() {
            assertThat(defaults.withRetentionCount(0).retentionCountClamped()).isEqualTo(1);
            assertThat(defaults.withRetentionCount(9999).retentionCountClamped()).isEqualTo(500);
            assertThat(defaults.withRetentionCount(50).retentionCountClamped()).isEqualTo(50);
        }
    }
}
