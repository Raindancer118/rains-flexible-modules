package de.raindancer.modules.sends;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SendsSettingsTest {

    private final SendsSettings defaults = SendsSettings.DEFAULTS;

    @Test
    @DisplayName("a send waits ten minutes unless the owner says otherwise")
    void tenMinutes() {
        assertThat(defaults.expiryMinutes()).isEqualTo(10);
        assertThat(defaults.lifetimeMillis()).isEqualTo(600_000L);
        assertThat(defaults.withExpiryMinutes(3).lifetimeMillis()).isEqualTo(180_000L);
    }

    @Test
    @DisplayName("nonsense in the file is clamped rather than believed")
    void clamped() {
        assertThat(defaults.withExpiryMinutes(0).lifetimeMillis()).isEqualTo(60_000L);
        assertThat(defaults.withMostWaiting(-4).mostWaitingClamped()).isEqualTo(1);
    }

    @Test
    @DisplayName("every with… changes its own component and nothing else")
    void withMethods() {
        assertThat(defaults.withMostWaiting(9).mostWaiting()).isEqualTo(9);
        assertThat(defaults.withMostWaiting(9).expiryMinutes()).isEqualTo(defaults.expiryMinutes());
        assertThat(defaults.withCooldownSeconds(0).cooldownSeconds()).isZero();
        assertThat(defaults.withRemindOnJoin(false).remindOnJoin()).isFalse();
        assertThat(defaults.withRemindOnJoin(false).cooldownSeconds()).isEqualTo(defaults.cooldownSeconds());
    }
}
