package de.raindancer.modules.economy.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("experience levels as points, the way Minecraft counts them")
class ExperienceRuleTest {

    private final ExperienceRule rule = new ExperienceRule();

    @Test
    @DisplayName("the points a level takes, in each of Minecraft's three bands")
    void levels() {
        assertThat(rule.pointsAtLevel(0)).isZero();
        assertThat(rule.pointsAtLevel(1)).isEqualTo(7);
        assertThat(rule.pointsAtLevel(16)).isEqualTo(352);
        assertThat(rule.pointsAtLevel(17)).isEqualTo(394);
        assertThat(rule.pointsAtLevel(30)).isEqualTo(1395);
        assertThat(rule.pointsAtLevel(31)).isEqualTo(1507);
        assertThat(rule.pointsAtLevel(32)).isEqualTo(1628);
        for (int level = 0; level < 100; level++) {
            assertThat(rule.pointsAtLevel(level + 1) - rule.pointsAtLevel(level))
                    .as("level %d", level).isEqualTo(rule.toNext(level));
        }
    }

    @Test
    @DisplayName("a total of points is a level and the points into the next")
    void level() {
        assertThat(rule.levelOf(0)).isZero();
        assertThat(rule.levelOf(1395)).isEqualTo(30);
        assertThat(rule.levelOf(1394)).isEqualTo(29);
        assertThat(rule.levelOf(1506)).isEqualTo(30);
    }

    @Test
    @DisplayName("buying levels: from where you are to that many levels higher, keeping the bar where it was")
    void buying() {
        assertThat(rule.pointsToGain(0, 30)).isEqualTo(1395);
        assertThat(rule.pointsToGain(rule.pointsAtLevel(10), 5))
                .isEqualTo(rule.pointsAtLevel(15) - rule.pointsAtLevel(10));
        int halfwayTo11 = rule.pointsAtLevel(10) + rule.toNext(10) / 2;
        int after = halfwayTo11 + rule.pointsToGain(halfwayTo11, 1);
        assertThat(rule.levelOf(after)).isEqualTo(11);
        assertThat(after - rule.pointsAtLevel(11)).as("about halfway again").isEqualTo(rule.toNext(11) / 2);
    }

    @Test
    @DisplayName("selling levels: never more than you have, all of it for 'all'")
    void selling() {
        int thirty = rule.pointsAtLevel(30);
        assertThat(rule.pointsToLose(thirty, 5)).isEqualTo(thirty - rule.pointsAtLevel(25));
        assertThat(rule.pointsToLose(thirty, 99)).isEqualTo(thirty);
        assertThat(rule.pointsToLose(100, Integer.MAX_VALUE)).isEqualTo(100);
        assertThat(rule.pointsToGain(rule.pointsAtLevel(ExperienceRule.MOST_LEVEL), 5)).as("no higher than the cap")
                .isZero();
    }
}
