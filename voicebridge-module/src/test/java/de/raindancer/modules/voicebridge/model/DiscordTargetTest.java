package de.raindancer.modules.voicebridge.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordTargetTest {

    @Test
    @DisplayName("a Discord id is 17 to 20 digits and nothing else")
    void snowflakes() {
        assertThat(DiscordTarget.isSnowflake("123456789012345678")).isTrue();
        assertThat(DiscordTarget.isSnowflake("12345678901234567")).isTrue();
        assertThat(DiscordTarget.isSnowflake("12345678901234567890")).isTrue();
        assertThat(DiscordTarget.isSnowflake("1234")).isFalse();
        assertThat(DiscordTarget.isSnowflake("123456789012345678901")).isFalse();
        assertThat(DiscordTarget.isSnowflake("12345678901234567a")).isFalse();
        assertThat(DiscordTarget.isSnowflake("")).isFalse();
        assertThat(DiscordTarget.isSnowflake(null)).isFalse();
    }

    @Test
    @DisplayName("an id pasted with spaces around it is still the id")
    void trims() {
        DiscordTarget target = new DiscordTarget(" 123456789012345678 ", "\t223456789012345678\n");

        assertThat(target.guildId()).isEqualTo("123456789012345678");
        assertThat(target.channelId()).isEqualTo("223456789012345678");
        assertThat(target.isComplete()).isTrue();
    }

    @Test
    @DisplayName("a missing id is empty, never null")
    void nullIsEmpty() {
        DiscordTarget target = new DiscordTarget(null, null);

        assertThat(target.guildId()).isEmpty();
        assertThat(target.isComplete()).isFalse();
    }
}
