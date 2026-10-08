package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a raffle's draw and how many tickets anybody may buy")
class RaffleRuleTest {

    private final RaffleRule rule = new RaffleRule();
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();

    @Test
    @DisplayName("every ticket is one equal share of the draw")
    void winner() {
        Map<UUID, Integer> tickets = new LinkedHashMap<>();
        tickets.put(ada, 1);
        tickets.put(bo, 3);
        assertThat(rule.winner(tickets, 0.0)).isEqualTo(ada);
        assertThat(rule.winner(tickets, 0.249)).isEqualTo(ada);
        assertThat(rule.winner(tickets, 0.25)).isEqualTo(bo);
        assertThat(rule.winner(tickets, 0.999)).isEqualTo(bo);
        assertThat(rule.winner(Map.of(), 0.5)).as("nobody bought").isNull();
        assertThat(rule.chance(3, 4)).isEqualTo(0.75);
        assertThat(rule.chance(0, 0)).isZero();
    }

    @Test
    @DisplayName("as many as wanted, within the per-player limit and the tickets left")
    void allowed() {
        assertThat(rule.allowed(5, 0, 0, 0, 0)).as("no limits").isEqualTo(5);
        assertThat(rule.allowed(5, 8, 10, 0, 0)).as("two left for this player").isEqualTo(2);
        assertThat(rule.allowed(5, 0, 0, 97, 100)).as("three left in the raffle").isEqualTo(3);
        assertThat(rule.allowed(5, 10, 10, 0, 0)).isZero();
        assertThat(rule.allowed(0, 0, 0, 0, 0)).isZero();
    }

    @Test
    @DisplayName("the house's share of the pot, rounded down")
    void fee() {
        assertThat(rule.fee(Money.of(1_000), 5)).isEqualTo(Money.of(50));
        assertThat(rule.fee(Money.of(19), 5)).isEqualTo(Money.ZERO);
        assertThat(rule.fee(Money.of(100), 90)).as("never more than half").isEqualTo(Money.of(50));
    }
}
