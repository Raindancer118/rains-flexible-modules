package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.model.QueueEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpenQueueRuleTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();
    private static final UUID CY = UUID.randomUUID();
    private static final MatchSpec DUEL = MatchSpec.duel("archer");
    private static final MatchSpec TEAMS = new MatchSpec(Mode.TEAMS, "gladiator", 1, 0, 2, null);

    private final OpenQueueRule rule = new OpenQueueRule();

    @Test
    @DisplayName("a queue with fewer than a fight takes is open, oldest waiting first; a full one is not")
    void open() {
        List<OpenQueueRule.Open> open = rule.open(List.of(
                new QueueEntry(BO, TEAMS, 20), new QueueEntry(ADA, TEAMS, 10), new QueueEntry(CY, DUEL, 5)));
        assertThat(open).hasSize(2);
        OpenQueueRule.Open teams = open.stream().filter(each -> each.spec().equals(TEAMS)).findFirst().orElseThrow();
        assertThat(teams.waiting()).containsExactly(ADA, BO);
        assertThat(teams.needed()).isEqualTo(4);
        assertThat(rule.open(List.of(new QueueEntry(ADA, DUEL, 1), new QueueEntry(BO, DUEL, 2)))).isEmpty();
    }

    @Test
    @DisplayName("mob waves for one are never somebody else's to join")
    void wavesForOne() {
        assertThat(rule.open(List.of(new QueueEntry(ADA, MatchSpec.waves(null, "gladiator"), 1)))).isEmpty();
    }

    @Test
    @DisplayName("somebody waiting is not told about their own queue")
    void notTheirOwn() {
        List<OpenQueueRule.Open> open = rule.open(List.of(new QueueEntry(ADA, DUEL, 1)));
        assertThat(rule.forViewer(open, ADA)).isEmpty();
        assertThat(rule.forViewer(open, BO)).hasSize(1);
    }
}
