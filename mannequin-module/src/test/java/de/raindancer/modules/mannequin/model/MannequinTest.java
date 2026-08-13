package de.raindancer.modules.mannequin.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MannequinTest {

    private final Mannequin base = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 1, 64, 2);

    @Test
    void noOverrideFallsBackToTheServerDefault() {
        assertThat(base.maxHealthOverride()).isNull();
        assertThat(base.resolvedMaxHealth(20.0)).isEqualTo(20.0);
    }

    @Test
    void anOverrideWins() {
        Mannequin tough = base.withMaxHealthOverride(100.0);

        assertThat(tough.maxHealthOverride()).isEqualTo(100.0);
        assertThat(tough.resolvedMaxHealth(20.0)).isEqualTo(100.0);
    }

    @Test
    void settingItBackToNullRestoresTheDefault() {
        Mannequin tough = base.withMaxHealthOverride(100.0);

        assertThat(tough.withMaxHealthOverride(null).resolvedMaxHealth(20.0)).isEqualTo(20.0);
    }

    @Test
    void aZeroOrNegativeOverrideIsRejected() {
        assertThatThrownBy(() -> base.withMaxHealthOverride(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withMaxHealthOverride(-5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAnchorBlockIsExactlyWhatWasPassedIn() {
        assertThat(base.x()).isEqualTo(1);
        assertThat(base.y()).isEqualTo(64);
        assertThat(base.z()).isEqualTo(2);
        assertThat(base.barrelY()).isEqualTo(63);
    }
}
