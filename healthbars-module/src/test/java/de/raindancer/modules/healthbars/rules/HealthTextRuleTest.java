package de.raindancer.modules.healthbars.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthTextRuleTest {

    private final HealthTextRule rule = new HealthTextRule();

    /** What a player reads, without the colours. */
    private String plain(HealthStyle style, double health, double most) {
        return rule.text(style, health, most).replaceAll("<[^>]+>", "");
    }

    @Test
    @DisplayName("hearts: a heart and the health, in green, yellow or red as it runs down")
    void hearts() {
        assertThat(plain(HealthStyle.HEARTS, 20, 20)).isEqualTo("❤ 20");
        assertThat(rule.text(HealthStyle.HEARTS, 20, 20)).contains("<green>20");
        assertThat(rule.text(HealthStyle.HEARTS, 8, 20)).contains("<yellow>8");
        assertThat(rule.text(HealthStyle.HEARTS, 4, 20)).contains("<red>4");
        assertThat(plain(HealthStyle.HEARTS, 300, 300)).isEqualTo("❤ 300");
    }

    @Test
    @DisplayName("halves are shown, everything finer rounded up, so a living thing never reads 0")
    void numbers() {
        assertThat(plain(HealthStyle.HEARTS, 7.5, 20)).isEqualTo("❤ 7.5");
        assertThat(plain(HealthStyle.HEARTS, 7.2, 20)).isEqualTo("❤ 7.5");
        assertThat(plain(HealthStyle.HEARTS, 0.1, 20)).isEqualTo("❤ 0.5");
    }

    @Test
    @DisplayName("bar: ten segments filled as far as the health goes, and the percent")
    void bar() {
        assertThat(plain(HealthStyle.BAR, 20, 20)).isEqualTo("██████████ 100%");
        assertThat(plain(HealthStyle.BAR, 12, 20)).isEqualTo("██████████ 60%");
        assertThat(rule.text(HealthStyle.BAR, 12, 20)).contains("<green>██████</green><dark_gray>████</dark_gray>");
        assertThat(plain(HealthStyle.BAR, 0.1, 20)).as("alive is at least a sliver and 1%").endsWith(" 1%");
        assertThat(rule.text(HealthStyle.BAR, 0.1, 20)).contains("<red>█</red>");
    }

    @Test
    @DisplayName("a style is read by its name; anything else is hearts")
    void styles() {
        assertThat(HealthStyle.of("bar")).isEqualTo(HealthStyle.BAR);
        assertThat(HealthStyle.of("HEARTS")).isEqualTo(HealthStyle.HEARTS);
        assertThat(HealthStyle.of(null)).isEqualTo(HealthStyle.HEARTS);
    }

    @Test
    @DisplayName("a mob shows while hurt or lately hit")
    void shown() {
        assertThat(rule.mobShown(20, 20, 0, 100_000, 15)).as("whole and long untouched").isFalse();
        assertThat(rule.mobShown(19, 20, 0, 100_000, 15)).as("hurt").isTrue();
        assertThat(rule.mobShown(20, 20, 90_000, 100_000, 15)).as("hit ten seconds ago, healed").isTrue();
        assertThat(rule.mobShown(20, 20, 80_000, 100_000, 15)).isFalse();
    }
}
