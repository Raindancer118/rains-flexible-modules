package de.raindancer.modules.voicebridge.rules;

import de.raindancer.modules.voicebridge.model.DiscordTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectReadinessRuleTest {

    private static final String GUILD = "123456789012345678";
    private static final String CHANNEL = "223456789012345678";
    private static final String TOKEN = "a.b.c";

    private final ConnectReadinessRule rule = new ConnectReadinessRule();

    @Test
    @DisplayName("token, server and channel: ready")
    void ready() {
        assertThat(rule.refusal(true, TOKEN, new DiscordTarget(GUILD, CHANNEL))).isEmpty();
    }

    @Test
    @DisplayName("switched off is said before anything else is missing")
    void switchedOff() {
        assertThat(rule.refusal(false, "", new DiscordTarget("", ""))).contains("voicebridge.not-ready.off");
    }

    @Test
    @DisplayName("each missing piece is named, in the order an owner would set them up")
    void namesWhatIsMissing() {
        assertThat(rule.refusal(true, " ", new DiscordTarget(GUILD, CHANNEL)))
                .contains("voicebridge.not-ready.no-token");
        assertThat(rule.refusal(true, TOKEN, new DiscordTarget("", CHANNEL)))
                .contains("voicebridge.not-ready.no-server");
        assertThat(rule.refusal(true, TOKEN, new DiscordTarget(GUILD, "")))
                .contains("voicebridge.not-ready.no-channel");
    }

    @Test
    @DisplayName("something that is not an id is told apart from nothing at all")
    void namesWhatIsWrong() {
        assertThat(rule.refusal(true, TOKEN, new DiscordTarget("my server", CHANNEL)))
                .contains("voicebridge.not-ready.bad-server");
        assertThat(rule.refusal(true, TOKEN, new DiscordTarget(GUILD, "#general")))
                .contains("voicebridge.not-ready.bad-channel");
    }

    @Test
    @DisplayName("a null token is a missing one")
    void nullToken() {
        assertThat(rule.refusal(true, null, new DiscordTarget(GUILD, CHANNEL)))
                .contains("voicebridge.not-ready.no-token");
    }
}
