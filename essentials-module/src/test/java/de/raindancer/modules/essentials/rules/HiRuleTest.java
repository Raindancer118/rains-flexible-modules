package de.raindancer.modules.essentials.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The "Say Hi!" button under a welcome line. */
class HiRuleTest {

    private static final UUID BO = UUID.nameUUIDFromBytes("bo".getBytes());
    private static final UUID CY = UUID.nameUUIDFromBytes("cy".getBytes());

    private final HiRule rule = new HiRule();

    @Test
    @DisplayName("somebody else may say hi to a newcomer, once")
    void greeting() {
        assertThat(rule.judge(new HiRule.Click(CY, BO, false)).isAllowed()).isTrue();
        assertThat(rule.judge(new HiRule.Click(CY, BO, true)).reason())
                .isEqualTo("essentials.welcome.hi-already");
    }

    @Test
    @DisplayName("nobody says hi to themselves")
    void notYourself() {
        assertThat(rule.judge(new HiRule.Click(BO, BO, false)).reason())
                .isEqualTo("essentials.welcome.hi-yourself");
    }

    @Test
    @DisplayName("the line is a greeting from the list, then the name")
    void line() {
        List<String> greetings = List.of("Hi", "Hey", "Welcome back");
        assertThat(HiRule.line(greetings, "Bo", 1)).isEqualTo("Hey Bo");
        assertThat(HiRule.line(greetings, "Bo", 5)).as("any index lands in the list").isEqualTo("Welcome back Bo");
        assertThat(HiRule.line(greetings, "Bo", -1)).isIn("Hi Bo", "Hey Bo", "Welcome back Bo");
        assertThat(HiRule.line(List.of(), "Bo", 0)).as("an emptied list still greets").isEqualTo("Hi Bo");
        assertThat(HiRule.line(List.of("  ", "Yo"), "Bo", 0)).as("blank lines are skipped").isEqualTo("Yo Bo");
    }
}
