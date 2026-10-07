package de.raindancer.modules.playerutils.rules;

import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Arguments are recognised by what they are, not by where they stand. */
class ArgumentRuleTest {

    private final ArgumentRule rule = new ArgumentRule();

    @Test
    @DisplayName("a number and a name, either way round")
    void eitherOrder() {
        Reading one = rule.read(Action.DAMAGE, new String[]{"3", "Lilly"});
        Reading two = rule.read(Action.DAMAGE, new String[]{"Lilly", "3"});

        assertThat(one.target()).contains("Lilly");
        assertThat(one.number("hearts")).isEqualTo(3);
        assertThat(two.target()).contains("Lilly");
        assertThat(two.number("hearts")).isEqualTo(3);
        assertThat(one.problems()).isEmpty();
    }

    @Test
    @DisplayName("nothing given: no target (yourself), and every default")
    void defaults() {
        Reading reading = rule.read(Action.DAMAGE, new String[0]);

        assertThat(reading.target()).isEmpty();
        assertThat(reading.number("hearts")).isEqualTo(5);
        assertThat(reading.isOn("lethal")).isFalse();
    }

    @Test
    @DisplayName("words and switches are found wherever they are")
    void wordsAndSwitches() {
        Reading launch = rule.read(Action.LAUNCH, new String[]{"forward", "Sam", "4"});
        Reading boom = rule.read(Action.EXPLODE, new String[]{"blocks", "3", "fire"});

        assertThat(launch.word("way")).isEqualTo("forward");
        assertThat(launch.number("power")).isEqualTo(4);
        assertThat(launch.target()).contains("Sam");
        assertThat(boom.isOn("blocks")).isTrue();
        assertThat(boom.isOn("fire")).isTrue();
        assertThat(boom.number("power")).isEqualTo(3);
    }

    @Test
    @DisplayName("a word left out is its first word")
    void defaultWord() {
        assertThat(rule.read(Action.LAUNCH, new String[0]).word("way")).isEqualTo("up");
        assertThat(rule.read(Action.FLY, new String[]{"Sam"}).word("state")).isEqualTo("toggle");
    }

    @Test
    @DisplayName("a number outside its range is refused with the range")
    void outOfRange() {
        Reading reading = rule.read(Action.LAUNCH, new String[]{"50"});

        assertThat(reading.problems()).singleElement().asString().contains("0.1").contains("10");
    }

    @Test
    @DisplayName("two names is one too many, and says which")
    void twoNames() {
        Reading reading = rule.read(Action.HEAL, new String[]{"Lilly", "Sam"});

        assertThat(reading.problems()).singleElement().asString().contains("Sam");
    }

    @Test
    @DisplayName("me and self mean the sender")
    void me() {
        assertThat(rule.read(Action.HEAL, new String[]{"me"}).target()).isEmpty();
        assertThat(rule.read(Action.HEAL, new String[]{"self"}).problems()).isEmpty();
    }

    @Test
    @DisplayName("sudo: the first word is who, everything after it is the command, untouched")
    void rest() {
        Reading reading = rule.read(Action.SUDO, new String[]{"Lilly", "c:hello", "there", "3"});

        assertThat(reading.target()).contains("Lilly");
        assertThat(reading.rest()).isEqualTo("c:hello there 3");
    }

    @Test
    @DisplayName("sudo without a command is a problem, not an empty command")
    void restMissing() {
        assertThat(rule.read(Action.SUDO, new String[]{"Lilly"}).problems()).isNotEmpty();
    }

    @Test
    @DisplayName("hearts accept a trailing h, sizes a trailing x or %")
    void suffixes() {
        assertThat(rule.read(Action.DAMAGE, new String[]{"2.5h"}).number("hearts")).isEqualTo(2.5);
        assertThat(rule.read(Action.SCALE, new String[]{"2x"}).number("size")).isEqualTo(2);
        assertThat(rule.read(Action.SCALE, new String[]{"50%"}).number("size")).isEqualTo(0.5);
    }
}
