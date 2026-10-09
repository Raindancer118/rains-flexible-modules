package de.raindancer.modules.essentials.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HouseRuleTextRuleTest {

    private final HouseRuleTextRule rule = new HouseRuleTextRule();

    @Test
    @DisplayName("a title and a text of sensible length pass")
    void fine() {
        assertThat(rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.TITLE, "Be kind")).isAllowed()).isTrue();
        assertThat(rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.TEXT, "x".repeat(256))).isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("blank or too long is refused, with the limit")
    void refused() {
        assertThat(rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.TITLE, "  ")).reason())
                .isEqualTo("essentials.rules.blank");
        var tooLong = rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.TITLE, "x".repeat(41)));
        assertThat(tooLong.reason()).isEqualTo("essentials.rules.too-long");
        assertThat(tooLong.detail()).isEqualTo("40");
        assertThat(rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.TEXT, "x".repeat(257))).isRefused())
                .isTrue();
    }

    @Test
    @DisplayName("a preset name is a word: letters, digits and dashes")
    void presetNames() {
        assertThat(HouseRuleTextRule.presetName("  Friendly SMP ")).isEqualTo("friendly-smp");
        assertThat(rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.PRESET, "friendly-smp")).isAllowed())
                .isTrue();
        assertThat(rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.PRESET, "a.b")).reason())
                .isEqualTo("essentials.rules.preset.bad-name");
        assertThat(rule.judge(new HouseRuleTextRule.Ask(HouseRuleTextRule.Part.PRESET, "x".repeat(33))).isRefused())
                .isTrue();
    }
}
