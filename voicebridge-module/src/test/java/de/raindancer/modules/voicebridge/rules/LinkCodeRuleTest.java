package de.raindancer.modules.voicebridge.rules;

import de.raindancer.modules.voicebridge.model.PendingLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LinkCodeRuleTest {

    private final LinkCodeRule rule = new LinkCodeRule();
    private final PendingLink pending = new PendingLink("K7M2Q-XB4ZT", UUID.randomUUID(), 10_000);

    @Test
    @DisplayName("the code as shown, before it runs out")
    void matches() {
        assertThat(rule.accepts(pending, "K7M2Q-XB4ZT", 9_999)).isTrue();
    }

    @Test
    @DisplayName("typed in lower case or with spaces around it, it is still the code")
    void forgiving() {
        assertThat(rule.accepts(pending, "  k7m2qxb4zt ", 0)).isTrue();
    }

    @Test
    @DisplayName("a wrong code, a blank one or an expired one is refused")
    void refuses() {
        assertThat(rule.accepts(pending, "K7M2Q-XB4ZY", 0)).isFalse();
        assertThat(rule.accepts(pending, "", 0)).isFalse();
        assertThat(rule.accepts(pending, null, 0)).isFalse();
        assertThat(rule.accepts(pending, "K7M2Q-XB4ZT", 10_000)).isFalse();
        assertThat(rule.accepts(null, "K7M2Q-XB4ZT", 0)).isFalse();
    }
}
