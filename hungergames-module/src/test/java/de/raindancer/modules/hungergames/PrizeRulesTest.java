package de.raindancer.modules.hungergames;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.ParticipantState;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.PrizeRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pot arithmetic and the order of places — pure, no server. */
class PrizeRulesTest {

    private final PrizeRules rules = new PrizeRules();
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
        assertThat(rules.houseCut(Money.of(1_000), 250)).as("clamped to the whole pot").isEqualTo(Money.of(1_000));
        assertThat(rules.houseCut(Money.of(1_000), -5)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("100 pays the whole pot to first place")
    void winnerTakesAll() {
        Map<UUID, Money> paid = rules.payouts(Money.of(4_000), 0, "100", List.of(List.of(a), List.of(b)));
        assertThat(paid).containsOnlyKeys(a).containsEntry(a, Money.of(4_000));
    }

    @Test
    @DisplayName("60,30,10 shares the pot after the cut by place")
    void split() {
        Map<UUID, Money> paid = rules.payouts(Money.of(10_000), 10, "60,30,10",
                List.of(List.of(a), List.of(b), List.of(c), List.of(d)));
        assertThat(paid).containsEntry(a, Money.of(5_400)).containsEntry(b, Money.of(2_700))
                .containsEntry(c, Money.of(900)).doesNotContainKey(d);
        assertThat(total(paid)).isEqualTo(Money.of(9_000));
    }

    @Test
    @DisplayName("fewer places than weights: the unused weights are dropped, nothing is left unpaid")
    void fewerPlaces() {
        Map<UUID, Money> paid = rules.payouts(Money.of(900), 0, "60,30,10", List.of(List.of(a), List.of(b)));
        assertThat(paid).containsEntry(a, Money.of(600)).containsEntry(b, Money.of(300));
    }

    @Test
    @DisplayName("rounding never pays more than was taken: the dust goes to first place and the sum is exact")
    void rounding() {
        Money pot = Money.of(1_001);
        Map<UUID, Money> paid = rules.payouts(pot, 7, "50,30,20", List.of(List.of(a), List.of(b), List.of(c)));
        Money net = pot.minus(rules.houseCut(pot, 7));
        assertThat(total(paid)).isEqualTo(net);
        assertThat(total(paid).isAtLeast(pot)).isFalse();
    }

    @Test
    @DisplayName("a team's place is shared evenly, the odd units going to the first members")
    void teamShare() {
        UUID e = UUID.randomUUID();
        Map<UUID, Money> paid = rules.payouts(Money.of(100), 0, "100", List.of(List.of(a, b, e)));
        assertThat(paid).containsEntry(a, Money.of(34)).containsEntry(b, Money.of(33)).containsEntry(e, Money.of(33));
        assertThat(total(paid)).isEqualTo(Money.of(100));
    }

    @Test
    @DisplayName("a split nobody can read, or an empty pot, falls back to winner-takes-all or nothing")
    void junk() {
        assertThat(rules.payouts(Money.of(500), 0, "abc,,-4", List.of(List.of(a), List.of(b)))).containsOnlyKeys(a);
        assertThat(rules.payouts(Money.of(500), 0, "0,0", List.of(List.of(a), List.of(b)))).containsOnlyKeys(a);
        assertThat(rules.payouts(Money.ZERO, 0, "100", List.of(List.of(a)))).isEmpty();
        assertThat(rules.payouts(Money.of(500), 100, "100", List.of(List.of(a)))).isEmpty();
        assertThat(rules.payouts(Money.of(500), 0, "100", List.of())).isEmpty();
    }

    private static Participant solo(UUID id, boolean alive) {
        return new Participant(id, "x", alive ? ParticipantState.ALIVE : ParticipantState.ELIMINATED, Optional.empty());
    }

    @Test
    @DisplayName("places: the winner first, then whoever fell last")
    void soloPlaces() {
        List<List<UUID>> places = rules.places(List.of(solo(a, true), solo(b, false), solo(c, false), solo(d, false)),
                List.of(d, c, b), new Winner.Solo(a));
        assertThat(places).containsExactly(List.of(a), List.of(b), List.of(c), List.of(d));
    }

    @Test
    @DisplayName("places: a team is out when its last member falls, and the winning team is first")
    void teamPlaces() {
        TeamId red = new TeamId("red");
        TeamId blue = new TeamId("blue");
        TeamId green = new TeamId("green");
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        UUID g1 = UUID.randomUUID();
        List<Participant> all = List.of(
                new Participant(r1, "r1", ParticipantState.ALIVE, Optional.of(red)),
                new Participant(r2, "r2", ParticipantState.ELIMINATED, Optional.of(red)),
                new Participant(b1, "b1", ParticipantState.ELIMINATED, Optional.of(blue)),
                new Participant(b2, "b2", ParticipantState.ELIMINATED, Optional.of(blue)),
                new Participant(g1, "g1", ParticipantState.ELIMINATED, Optional.of(green)));
        // green fell first, then blue's first, red's second, blue's last
        List<List<UUID>> places = rules.places(all, List.of(g1, b1, r2, b2), new Winner.Team(red, Set.of(r1, r2)));
        assertThat(places).hasSize(3);
        assertThat(places.get(0)).containsExactlyInAnyOrder(r1, r2);
        assertThat(places.get(1)).containsExactlyInAnyOrder(b1, b2);
        assertThat(places.get(2)).containsExactly(g1);
    }

    @Test
    @DisplayName("nobody won: no places at all")
    void noWinner() {
        assertThat(rules.places(List.of(solo(a, true), solo(b, true)), List.of(), new Winner.None())).isEmpty();
    }

    @Test
    @DisplayName("an unreadable-but-valid split is read in order, ignoring spaces")
    void weights() {
        Map<UUID, Money> paid = new LinkedHashMap<>(rules.payouts(Money.of(1_000), 0, " 70 , 30 ",
                List.of(List.of(a), List.of(b))));
        assertThat(paid).containsEntry(a, Money.of(700)).containsEntry(b, Money.of(300));
    }
}
