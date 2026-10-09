package de.raindancer.modules.speedrun;

import de.raindancer.core.social.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pot arithmetic and the order of places — pure, no server. */
class SpeedrunPrizeRulesTest {

    private final SpeedrunPrizeRules rules = new SpeedrunPrizeRules();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    private static Money total(Map<UUID, Money> paid) {
        return paid.values().stream().reduce(Money.ZERO, Money::plus);
    }

    @Test
    @DisplayName("the house cut is a percent of the pot, rounded down, and zero by default")
    void cut() {
        assertThat(rules.houseCut(Money.of(1_000), 0)).isEqualTo(Money.ZERO);
        assertThat(rules.houseCut(Money.of(1_000), 10)).isEqualTo(Money.of(100));
        assertThat(rules.houseCut(Money.of(999), 10)).isEqualTo(Money.of(99));
        assertThat(rules.houseCut(Money.of(1_000), 250)).isEqualTo(Money.of(1_000));
        assertThat(rules.houseCut(Money.of(1_000), -5)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("100 pays the whole pot to first place, a winning side sharing it evenly")
    void winnerTakesAll() {
        Map<UUID, Money> paid = rules.payouts(Money.of(4_000), 0, "100", List.of(List.of(a, b), List.of(c)));
        assertThat(paid).containsOnlyKeys(a, b).containsEntry(a, Money.of(2_000)).containsEntry(b, Money.of(2_000));
    }

    @Test
    @DisplayName("60,30,10 shares what is left after the cut by place")
    void split() {
        Map<UUID, Money> paid = rules.payouts(Money.of(10_000), 10, "60,30,10",
                List.of(List.of(a), List.of(b), List.of(c), List.of(d)));
        assertThat(paid).containsEntry(a, Money.of(5_400)).containsEntry(b, Money.of(2_700))
                .containsEntry(c, Money.of(900)).doesNotContainKey(d);
        assertThat(total(paid)).isEqualTo(Money.of(9_000));
    }

    @Test
    @DisplayName("rounding never pays more than was taken: the dust goes to first place and the sum is exact")
    void rounding() {
        Money pot = Money.of(1_001);
        Map<UUID, Money> paid = rules.payouts(pot, 7, "50,30,20", List.of(List.of(a), List.of(b), List.of(c)));
        assertThat(total(paid)).isEqualTo(pot.minus(rules.houseCut(pot, 7)));
        Map<UUID, Money> team = rules.payouts(Money.of(100), 0, "100", List.of(List.of(a, b, c)));
        assertThat(team).containsEntry(a, Money.of(34)).containsEntry(b, Money.of(33)).containsEntry(c, Money.of(33));
    }

    @Test
    @DisplayName("an unreadable split falls back to winner-takes-all; an empty pot or no place pays nothing")
    void junk() {
        assertThat(rules.payouts(Money.of(500), 0, "x,,-4", List.of(List.of(a), List.of(b)))).containsOnlyKeys(a);
        assertThat(rules.payouts(Money.ZERO, 0, "100", List.of(List.of(a)))).isEmpty();
        assertThat(rules.payouts(Money.of(500), 100, "100", List.of(List.of(a)))).isEmpty();
        assertThat(rules.payouts(Money.of(500), 0, "100", List.of())).isEmpty();
    }

    @Test
    @DisplayName("places: the winners first, everybody else after them; no winners, no places")
    void places() {
        Set<UUID> everybody = new LinkedHashSet<>(List.of(a, b, c, d));
        assertThat(rules.places(everybody, Set.of(a, b))).hasSize(2);
        assertThat(rules.places(everybody, Set.of(a, b)).get(0)).containsExactlyInAnyOrder(a, b);
        assertThat(rules.places(everybody, Set.of(a, b)).get(1)).containsExactlyInAnyOrder(c, d);
        assertThat(rules.places(Set.of(a, b), Set.of(a, b))).hasSize(1);
        assertThat(rules.places(everybody, Set.of())).isEmpty();
        assertThat(rules.places(everybody, Set.of(UUID.randomUUID()))).as("a winner who is not a racer").isEmpty();
    }

    @Test
    @DisplayName("winners: a plain race that reached its goal is won by everybody; a reset, a death or a silent game is won by nobody")
    void winners() {
        Set<UUID> racers = Set.of(a, b, c);
        assertThat(rules.winners(racers, true, java.util.Optional.empty(), "advancement:minecraft:end/kill_dragon"))
                .isEqualTo(racers);
        assertThat(rules.winners(racers, true, java.util.Optional.empty(), "death:" + a)).isEmpty();
        assertThat(rules.winners(racers, true, java.util.Optional.empty(), "death-all")).isEmpty();
        assertThat(rules.winners(racers, true, java.util.Optional.empty(), "admin-reset")).isEmpty();
        assertThat(rules.winners(racers, false, java.util.Optional.of(Set.of(a)), "advancement:x")).containsExactly(a);
        assertThat(rules.winners(racers, false, java.util.Optional.empty(), "advancement:x"))
                .as("a game that says nothing is not guessed at").isEmpty();
    }
}
