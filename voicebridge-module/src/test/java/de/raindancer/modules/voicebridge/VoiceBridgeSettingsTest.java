package de.raindancer.modules.voicebridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceBridgeSettingsTest {

    private final VoiceBridgeSettings defaults = VoiceBridgeSettings.DEFAULTS;

    @Test
    @DisplayName("every default, spelled out")
    void defaults() {
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.guildId()).isEmpty();
        assertThat(defaults.channelId()).isEmpty();
        assertThat(defaults.groupName()).isEqualTo("Discord");
        assertThat(defaults.discordVolumePercent()).isEqualTo(100);
        assertThat(defaults.gameVolumePercent()).isEqualTo(100);
        assertThat(defaults.announceDiscordJoins()).isTrue();
        assertThat(defaults.bufferFrames()).isEqualTo(5);
    }

    @Test
    @DisplayName("the target is read from the two ids")
    void target() {
        VoiceBridgeSettings set = defaults.withGuildId("123456789012345678").withChannelId("223456789012345678");

        assertThat(set.target().isComplete()).isTrue();
        assertThat(set.target().channelId()).isEqualTo("223456789012345678");
    }

    @Test
    @DisplayName("nonsense volumes and buffers are clamped rather than believed")
    void clamps() {
        VoiceBridgeSettings broken = defaults.withDiscordVolumePercent(-5).withGameVolumePercent(9000)
                .withBufferFrames(0);

        assertThat(broken.discordVolumeClamped()).isZero();
        assertThat(broken.gameVolumeClamped()).isEqualTo(VoiceBridgeSettings.LOUDEST);
        assertThat(broken.bufferFramesClamped()).isEqualTo(2);
        assertThat(defaults.withBufferFrames(500).bufferFramesClamped()).isEqualTo(25);
    }

    @Test
    @DisplayName("audio waits for two frames before playing, never more than the buffer holds")
    void prebuffer() {
        assertThat(defaults.prebufferFrames()).isEqualTo(2);
        assertThat(defaults.withBufferFrames(2).prebufferFrames()).isEqualTo(2);
    }

    @Test
    @DisplayName("a blank group name falls back to Discord, since SVC refuses a nameless group")
    void groupNameFallsBack() {
        assertThat(defaults.withGroupName("  ").groupNameOrDefault()).isEqualTo("Discord");
        assertThat(defaults.withGroupName(" Stammtisch ").groupNameOrDefault()).isEqualTo("Stammtisch");
    }

    @Test
    @DisplayName("every with… changes its own component and nothing else")
    void withMethodsChangeOneThing() {
        VoiceBridgeSettings changed = defaults.withEnabled(false);
        assertThat(changed.enabled()).isFalse();
        assertThat(changed.groupName()).isEqualTo(defaults.groupName());

        assertThat(defaults.withAnnounceDiscordJoins(false).announceDiscordJoins()).isFalse();
        assertThat(defaults.withAnnounceDiscordJoins(false).bufferFrames()).isEqualTo(5);
        assertThat(defaults.withGameVolumePercent(50).gameVolumePercent()).isEqualTo(50);
        assertThat(defaults.withGameVolumePercent(50).discordVolumePercent()).isEqualTo(100);
    }
}
