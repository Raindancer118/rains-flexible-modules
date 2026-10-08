package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.RouletteBet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouletteRuleTest {

    private final RouletteRule rule = new RouletteRule();

    @Test
    @DisplayName("the wheel is the European one: 37 pockets, each once, 18 red, 18 black and a green zero")
    void wheel() {
        assertThat(new HashSet<>(RouletteRule.WHEEL)).hasSize(37);
        assertThat(RouletteRule.WHEEL.getFirst()).isZero();
        long red = RouletteRule.WHEEL.stream().filter(n -> rule.colourOf(n) == RouletteRule.Colour.RED).count();
        long black = RouletteRule.WHEEL.stream().filter(n -> rule.colourOf(n) == RouletteRule.Colour.BLACK).count();
        assertThat(red).isEqualTo(18);
        assertThat(black).isEqualTo(18);
        assertThat(rule.colourOf(0)).isEqualTo(RouletteRule.Colour.GREEN);
        assertThat(rule.colourOf(32)).as("the pocket beside zero").isEqualTo(RouletteRule.Colour.RED);
        assertThat(rule.colourOf(26)).isEqualTo(RouletteRule.Colour.BLACK);
        // Around the wheel the colours alternate, apart from the zero.
        for (int i = 1; i < 36; i++) {
            assertThat(rule.colourOf(RouletteRule.WHEEL.get(i))).isNotEqualTo(rule.colourOf(RouletteRule.WHEEL.get(i + 1)));
        }
    }

    @Test
    @DisplayName("each bet wins on exactly the pockets it names, and zero loses every even-money bet")
    void winning() {
        assertThat(rule.wins(RouletteBet.on(RouletteBet.Kind.RED), 32)).isTrue();
        assertThat(rule.wins(RouletteBet.on(RouletteBet.Kind.BLACK), 32)).isFalse();
        assertThat(rule.wins(RouletteBet.on(RouletteBet.Kind.EVEN), 0)).isFalse();
        assertThat(rule.wins(RouletteBet.on(RouletteBet.Kind.LOW), 18)).isTrue();
        assertThat(rule.wins(RouletteBet.on(RouletteBet.Kind.HIGH), 18)).isFalse();
        assertThat(rule.wins(RouletteBet.on(RouletteBet.Kind.SECOND_DOZEN), 24)).isTrue();
        assertThat(rule.wins(RouletteBet.on(RouletteBet.Kind.GREEN), 0)).isTrue();
        assertThat(rule.wins(RouletteBet.number(17), 17)).isTrue();
        assertThat(rule.wins(RouletteBet.number(17), 18)).isFalse();
    }

    @Test
    @DisplayName("every bet returns exactly one minus the house edge on average")
    void fairness() {
        List<RouletteBet> bets = new ArrayList<>();
        for (RouletteBet.Kind kind : RouletteBet.Kind.values()) {
            if (kind != RouletteBet.Kind.NUMBER) {
                bets.add(RouletteBet.on(kind));
            }
        }
        bets.add(RouletteBet.number(0));
        bets.add(RouletteBet.number(23));
        for (RouletteBet bet : bets) {
            double expected = 0;
            for (int pocket = 0; pocket <= 36; pocket++) {
                if (rule.wins(bet, pocket)) {
                    expected += rule.payout(Money.of(1_000_000), bet, 0.03).minor() / 1_000_000.0 / 37;
                }
            }
            assertThat(expected).as(bet.label()).isCloseTo(0.97, within(0.0001));
        }
    }

    @Test
    @DisplayName("a spin lands on a pocket of the wheel")
    void spin() {
        for (int i = 0; i < 500; i++) {
            assertThat(rule.spin(bound -> bound - 1)).isBetween(0, 36);
        }
        assertThat(rule.spin(bound -> 0)).isZero();
    }
}
