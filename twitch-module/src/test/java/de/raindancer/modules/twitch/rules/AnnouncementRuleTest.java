package de.raindancer.modules.twitch.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementRuleTest {

    private final AnnouncementRule rule = new AnnouncementRule();

    @Test
    @DisplayName("a channel from another server is used only if it is a Twitch login; anything else is this server's own")
    void channel() {
        assertThat(rule.channelOf("Lillyyxoxo_", "mine")).isEqualTo("lillyyxoxo_");
        assertThat(rule.channelOf("evil.example/phish", "mine")).isEqualTo("mine");
        assertThat(rule.channelOf("a b", "mine")).isEqualTo("mine");
        assertThat(rule.channelOf("", "mine")).isEqualTo("mine");
        assertThat(rule.channelOf(null, "mine")).isEqualTo("mine");
        assertThat(rule.channelOf("x".repeat(26), "mine")).isEqualTo("mine");
    }

    @Test
    @DisplayName("the link is always Twitch's, built from the channel here")
    void url() {
        assertThat(rule.urlOf("lillyyxoxo_")).isEqualTo("https://www.twitch.tv/lillyyxoxo_");
    }

    @Test
    @DisplayName("text from another server loses control characters and is cut to length")
    void text() {
        assertThat(rule.clean("Build\nthe\tcastle\u0007", 50)).isEqualTo("Build the castle");
        assertThat(rule.clean("x".repeat(300), 140)).hasSize(140);
        assertThat(rule.clean(null, 10)).isEmpty();
    }
}
