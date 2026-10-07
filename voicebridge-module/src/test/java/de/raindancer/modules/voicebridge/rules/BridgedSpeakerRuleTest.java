package de.raindancer.modules.voicebridge.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BridgedSpeakerRuleTest {

    private final BridgedSpeakerRule rule = new BridgedSpeakerRule();
    private final UUID bridge = UUID.randomUUID();

    @Test
    @DisplayName("somebody in the bridge group is heard on Discord")
    void inTheGroup() {
        assertThat(rule.bridges(bridge, bridge)).isTrue();
    }

    @Test
    @DisplayName("somebody in another group, or none, is never sent to Discord")
    void outsideTheGroup() {
        assertThat(rule.bridges(bridge, UUID.randomUUID())).isFalse();
        assertThat(rule.bridges(bridge, null)).isFalse();
    }

    @Test
    @DisplayName("with no bridge group yet nobody is sent, rather than everybody")
    void noGroupYet() {
        assertThat(rule.bridges(null, null)).isFalse();
        assertThat(rule.bridges(null, UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("it says what it decides")
    void describes() {
        assertThat(rule.describe()).isNotBlank();
    }
}
