package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Award;
import de.raindancer.modules.ctf.model.Fighter;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Round;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("awards at the end of a round")
class AwardRuleTest {

    private final AwardRule rule = new AwardRule();

    @Test
    @DisplayName("each award goes to who did most of its thing")
    void awards() {
        Round round = Rounds.round(GameType.CLASSIC);
        Fighter runner = Rounds.join(round, "runner", 0, Role.SCOUT);
        Fighter keeper = Rounds.join(round, "keeper", 1, Role.SOLDIER);
        Fighter clumsy = Rounds.join(round, "clumsy", 0, Role.SOLDIER);
        runner.pickedUp(0);
        runner.captured(12_000);
        runner.stoppedCarrying(12_000, false);
        runner.captured(20_000);
        keeper.returned(false);
        keeper.returned(true);
        keeper.defenceKill();
        clumsy.pickedUp(0);
        clumsy.stoppedCarrying(1_000, true);
        clumsy.pickedUp(2_000);
        clumsy.stoppedCarrying(3_000, true);
        clumsy.escortKill();

        Map<Award, java.util.UUID> awards = rule.awards(round.fighters());

        assertThat(awards.get(Award.MVP)).isEqualTo(runner.id());
        assertThat(awards.get(Award.SPEED_DEMON)).isEqualTo(runner.id());
        assertThat(awards.get(Award.BEST_DEFENDER)).isEqualTo(keeper.id());
        assertThat(awards.get(Award.BUTTERFINGERS)).isEqualTo(clumsy.id());
        assertThat(awards.get(Award.BODYGUARD)).isEqualTo(clumsy.id());
        assertThat(awards.get(Award.FLAG_RUNNER)).isEqualTo(runner.id());
    }

    @Test
    @DisplayName("nobody gets an award for doing none of it")
    void nothing() {
        Round round = Rounds.round(GameType.CLASSIC);
        Rounds.join(round, "idle", 0, Role.SOLDIER);
        assertThat(rule.awards(round.fighters())).isEmpty();
    }

    @Test
    @DisplayName("a tie goes to who did it with fewer deaths, then by name — never at random")
    void ties() {
        Round round = Rounds.round(GameType.CLASSIC);
        Fighter a = Rounds.join(round, "amy", 0, Role.SOLDIER);
        Fighter b = Rounds.join(round, "bob", 1, Role.SOLDIER);
        a.returned(false);
        b.returned(false);
        a.died(0);
        assertThat(rule.awards(round.fighters()).get(Award.BEST_DEFENDER)).isEqualTo(b.id());
    }
}
