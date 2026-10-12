package de.raindancer.modules.arena.model;

import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.api.WaveSpec;
import de.raindancer.modules.arena.api.MatchSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FightTest {

    private static final Pit PIT = Pit.named("main", "arena");
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();
    private final UUID di = UUID.randomUUID();

    private Fight duel(int bestOf) {
        Fight fight = new Fight(UUID.randomUUID(), MatchSpec.duel("gladiator").withBestOf(bestOf), PIT,
                List.of(Set.of(ada), Set.of(bo)));
        fight.begin();
        return fight;
    }

    @Test
    @DisplayName("a duel of one round ends the moment one of them is beaten, and the other side has won")
    void singleRound() {
        Fight fight = duel(1);

        Fight.Outcome outcome = fight.beaten(bo, ada).orElseThrow();

        assertThat(outcome.side()).isEqualTo(0);
        assertThat(outcome.fightOver()).isTrue();
        assertThat(fight.state()).isEqualTo(Fight.State.OVER);
        assertThat(fight.kills(ada)).isEqualTo(1);
    }

    @Test
    @DisplayName("best of three: a round is won, then the next starts with both standing, and two rounds decide it")
    void bestOfThree() {
        Fight fight = duel(3);

        Fight.Outcome first = fight.beaten(bo, ada).orElseThrow();
        assertThat(first.fightOver()).isFalse();
        assertThat(fight.state()).isEqualTo(Fight.State.BETWEEN_ROUNDS);

        fight.nextRound();
        assertThat(fight.isStanding(bo)).isTrue();
        assertThat(fight.round()).isEqualTo(2);
        fight.begin();
        assertThat(fight.beaten(ada, bo).orElseThrow().fightOver()).isFalse();

        fight.nextRound();
        fight.begin();
        Fight.Outcome decider = fight.beaten(ada, bo).orElseThrow();
        assertThat(decider.fightOver()).isTrue();
        assertThat(decider.side()).isEqualTo(1);
        assertThat(fight.roundsWon(1)).isEqualTo(2);
        assertThat(fight.roundsWon(0)).isEqualTo(1);
    }

    @Test
    @DisplayName("an even number of rounds is made odd — nobody can be tied after all of them")
    void bestOfIsOdd() {
        assertThat(MatchSpec.duel("x").withBestOf(2).bestOf()).isEqualTo(3);
        assertThat(MatchSpec.duel("x").withBestOf(0).bestOf()).isEqualTo(1);
        assertThat(MatchSpec.duel("x").withBestOf(99).bestOf()).isEqualTo(MatchSpec.MOST_ROUNDS);
    }

    @Test
    @DisplayName("somebody leaving in the middle of a round loses it, and the fight with it in a duel")
    void leavingForfeits() {
        Fight fight = duel(3);

        Fight.Outcome outcome = fight.left(bo).orElseThrow();

        assertThat(outcome.side()).isEqualTo(0);
        assertThat(outcome.fightOver()).isTrue();
    }

    @Test
    @DisplayName("leaving between rounds ends it too — a side with nobody on it cannot win the next")
    void leavingBetweenRounds() {
        Fight fight = duel(3);
        fight.beaten(ada, bo);

        Fight.Outcome outcome = fight.left(bo).orElseThrow();

        assertThat(outcome.side()).isEqualTo(0);
        assertThat(outcome.fightOver()).isTrue();
    }

    @Test
    @DisplayName("free for all: nothing is decided while two are standing, and the last one wins")
    void freeForAll() {
        Fight fight = new Fight(UUID.randomUUID(), new MatchSpec(Mode.FFA, "knight", 1, 0, 1, null), PIT,
                List.of(Set.of(ada), Set.of(bo), Set.of(cy)));
        fight.begin();

        assertThat(fight.beaten(bo, ada)).isEmpty();
        Fight.Outcome outcome = fight.beaten(cy, ada).orElseThrow();

        assertThat(outcome.side()).isEqualTo(0);
        assertThat(fight.kills(ada)).isEqualTo(2);
    }

    @Test
    @DisplayName("teams: a team is out when its last member is, not before; team mates are not opponents")
    void teams() {
        Fight fight = new Fight(UUID.randomUUID(), new MatchSpec(Mode.TEAMS, "knight", 1, 0, 2, null), PIT,
                List.of(Set.of(ada, bo), Set.of(cy, di)));
        fight.begin();

        assertThat(fight.opponents(ada, bo)).isFalse();
        assertThat(fight.opponents(ada, cy)).isTrue();
        assertThat(fight.beaten(cy, ada)).isEmpty();
        assertThat(fight.beaten(di, bo).orElseThrow().side()).isEqualTo(0);
    }

    @Test
    @DisplayName("nobody is anybody's opponent before the start, between rounds, or after the end")
    void onlyWhileFighting() {
        Fight fight = new Fight(UUID.randomUUID(), MatchSpec.duel("x").withBestOf(3), PIT,
                List.of(Set.of(ada), Set.of(bo)));
        assertThat(fight.opponents(ada, bo)).isFalse();
        fight.begin();
        assertThat(fight.opponents(ada, bo)).isTrue();
        fight.beaten(bo, ada);
        assertThat(fight.opponents(ada, bo)).isFalse();
    }

    @Test
    @DisplayName("time running out is a draw that ends the fight")
    void timeUp() {
        Fight fight = duel(3);

        Fight.Outcome outcome = fight.timeUp();

        assertThat(outcome.side()).isEqualTo(-1);
        assertThat(outcome.fightOver()).isTrue();
    }

    @Test
    @DisplayName("waves: a wave is cleared when its last mob dies; the run ends at the last wave, or when everybody is down")
    void waves() {
        Fight fight = new Fight(UUID.randomUUID(), MatchSpec.waves(WaveSpec.PRESETS.get("warmup").withWaves(2),
                "gladiator"), PIT, List.of(Set.of(ada, bo)));
        fight.begin();
        UUID zombie = UUID.randomUUID();
        UUID skeleton = UUID.randomUUID();

        fight.waveStarted(Set.of(zombie, skeleton));
        assertThat(fight.opponents(zombie, ada)).isTrue();
        assertThat(fight.opponents(ada, bo)).as("players in a wave run are on one side").isFalse();
        assertThat(fight.mobGone(zombie, ada)).isFalse();
        assertThat(fight.mobGone(skeleton, bo)).isTrue();
        assertThat(fight.lastWaveCleared()).isFalse();

        fight.waveStarted(Set.of(UUID.randomUUID()));
        assertThat(fight.wave()).isEqualTo(2);
        assertThat(fight.beaten(ada, null)).isEmpty();
        assertThat(fight.beaten(bo, null).orElseThrow().side()).as("the mobs won").isEqualTo(-1);
        assertThat(fight.kills(ada)).isEqualTo(1);
    }

    @Test
    @DisplayName("a beaten player cannot be beaten twice, and somebody not in it changes nothing")
    void idempotent() {
        Fight fight = new Fight(UUID.randomUUID(), new MatchSpec(Mode.FFA, "knight", 1, 0, 1, null), PIT,
                List.of(Set.of(ada), Set.of(bo), Set.of(cy)));
        fight.begin();
        fight.beaten(bo, ada);

        assertThat(fight.beaten(bo, ada)).isEmpty();
        assertThat(fight.beaten(UUID.randomUUID(), ada)).isEmpty();
        assertThat(fight.kills(ada)).isEqualTo(1);
    }
}
