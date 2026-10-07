package de.raindancer.modules.playerutils.rules;

import de.raindancer.core.moderation.players.PlayerBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WipeRuleTest {

    private final WipeRule rule = new WipeRule();

    @Test
    @DisplayName("nothing named: inventory, advancements and experience")
    void standard() {
        assertThat(rule.parts(List.of())).isEqualTo(PlayerBody.Wipe.STANDARD);
        assertThat(rule.parts(List.of("confirm"))).isEqualTo(PlayerBody.Wipe.STANDARD);
    }

    @Test
    @DisplayName("named parts only, or everything")
    void named() {
        assertThat(rule.parts(List.of("xp", "enderchest")))
                .isEqualTo(EnumSet.of(PlayerBody.Wipe.EXPERIENCE, PlayerBody.Wipe.ENDER_CHEST));
        assertThat(rule.parts(List.of("all"))).isEqualTo(EnumSet.allOf(PlayerBody.Wipe.class));
    }

    @Test
    @DisplayName("described in words, in a fixed order")
    void words() {
        assertThat(rule.describe(PlayerBody.Wipe.STANDARD)).isEqualTo("inventory, advancements and experience");
    }
}
