package de.raindancer.modules.essentials.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A reaction button under somebody's line — Say Hi! on a join, Congrats! on an advancement. */
class ReactionRuleTest {

    private static final UUID BO = UUID.nameUUIDFromBytes("bo".getBytes());
    private static final UUID CY = UUID.nameUUIDFromBytes("cy".getBytes());

    private final ReactionRule hi = new ReactionRule("essentials.welcome.hi-yourself", "essentials.welcome.hi-already");

    @Test
    @DisplayName("somebody else may react, once")
    void once() {
        assertThat(hi.judge(new ReactionRule.Click(CY, BO, false)).isAllowed()).isTrue();
        assertThat(hi.judge(new ReactionRule.Click(CY, BO, true)).reason()).isEqualTo("essentials.welcome.hi-already");
    }

    @Test
    @DisplayName("nobody reacts to themselves")
    void notYourself() {
        assertThat(hi.judge(new ReactionRule.Click(BO, BO, false)).reason()).isEqualTo("essentials.welcome.hi-yourself");
    }

    @Test
    @DisplayName("each kind of reaction answers in its own words")
    void ownWords() {
        ReactionRule congrats = new ReactionRule("essentials.congrats.yourself", "essentials.congrats.already");
        assertThat(congrats.judge(new ReactionRule.Click(BO, BO, false)).reason()).isEqualTo("essentials.congrats.yourself");
    }

    @Test
    @DisplayName("the line is a phrase from the list, then the name")
    void line() {
        List<String> phrases = List.of("Hi", "Hey", "Welcome back");
        assertThat(ReactionRule.line(phrases, "Bo", 1, "Hi")).isEqualTo("Hey Bo");
        assertThat(ReactionRule.line(phrases, "Bo", 5, "Hi")).as("any index lands in the list").isEqualTo("Welcome back Bo");
        assertThat(ReactionRule.line(phrases, "Bo", -1, "Hi")).isIn("Hi Bo", "Hey Bo", "Welcome back Bo");
        assertThat(ReactionRule.line(List.of(), "Bo", 0, "GG")).as("an emptied list still reacts").isEqualTo("GG Bo");
        assertThat(ReactionRule.line(List.of("  ", "Yo"), "Bo", 0, "Hi")).as("blank lines are skipped").isEqualTo("Yo Bo");
    }

    @Test
    @DisplayName("{name} puts the name where the joke needs it")
    void namePlacement() {
        assertThat(ReactionRule.line(List.of("Took you long enough, {name}"), "Bo", 0, "GG"))
                .isEqualTo("Took you long enough, Bo");
        assertThat(ReactionRule.line(List.of("{name} is carrying the server"), "Bo", 0, "GG"))
                .isEqualTo("Bo is carrying the server");
        assertThat(ReactionRule.line(List.of("GG"), "Bo", 0, "GG")).as("without it, the name goes last")
                .isEqualTo("GG Bo");
    }
}
