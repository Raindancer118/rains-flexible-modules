package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.model.NpcRole;
import de.raindancer.modules.arena.rules.PairingRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnsweredInvitesTest {

    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();

    @Test
    @DisplayName("a second click on [Accept] is known as one, by the one who said yes, for a minute")
    void secondClick() {
        AnsweredInvites answered = new AnsweredInvites();
        answered.remember("2ef31d", ada, 1_000);

        assertThat(answered.answeredBy("2ef31d", ada, 2_000)).isTrue();
        assertThat(answered.answeredBy("2ef31d", bo, 2_000)).as("somebody else's click").isFalse();
        assertThat(answered.answeredBy("other", ada, 2_000)).isFalse();
        assertThat(answered.answeredBy("2ef31d", ada, 1_000 + 60_001)).as("forgotten after a minute").isFalse();
    }

    @Test
    @DisplayName("a newer agreed fight replaces an older one with any of the same players")
    void newerAgreementWins() {
        Deque<PairingRule.Group> agreed = new ArrayDeque<>();
        PairingRule.Group older = new PairingRule.Group(MatchSpec.duel("mace"), List.of(Set.of(ada, bo)));
        PairingRule.Group others = new PairingRule.Group(MatchSpec.duel("knight"), List.of(Set.of(cy)));
        agreed.add(older);
        agreed.add(others);
        PairingRule.Group newer = new PairingRule.Group(MatchSpec.duel("mace"), List.of(Set.of(bo), Set.of(ada)));

        QueueService.replaceOverlapping(agreed, newer);

        assertThat(agreed).containsExactly(others, newer);
    }

    @Test
    @DisplayName("the watcher is a gladiator role of its own, found by its name")
    void watcherRole() {
        assertThat(NpcRole.parse("watcher")).contains(NpcRole.WATCHER);
        assertThat(NpcRole.WATCHER.purpose()).containsIgnoringCase("watch");
    }
}
