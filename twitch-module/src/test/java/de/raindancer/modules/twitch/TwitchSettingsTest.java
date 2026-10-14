package de.raindancer.modules.twitch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchSettingsTest {

    @Test
    @DisplayName("the streamer is Lilly, in the game as on Twitch, and near is 32 blocks")
    void defaults() {
        assertThat(TwitchSettings.DEFAULTS.channelClean()).isEqualTo("lillyyxoxo_");
        assertThat(TwitchSettings.DEFAULTS.streamerName()).isEqualTo("lillyyxoxo");
        assertThat(TwitchSettings.DEFAULTS.nearBlocks()).isEqualTo(32);
    }
}
