package de.raindancer.modules.ctf.service;

import de.raindancer.modules.ctf.CtfSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the lobby's ballot")
class VoteServiceTest {

    private final UUID amy = UUID.nameUUIDFromBytes("amy".getBytes());
    private final UUID bob = UUID.nameUUIDFromBytes("bob".getBytes());
    private final VoteService votes = new VoteService(CtfSettings.DEFAULTS);

    @Test
    @DisplayName("a vote counts once per player and can be changed")
    void counts() {
        votes.open(List.of("a", "b"), List.of("CLASSIC", "NEUTRAL"), List.of("NONE", "TINY"));
        assertThat(votes.cast(amy, VoteService.Question.MAP, "a")).isTrue();
        assertThat(votes.cast(amy, VoteService.Question.MAP, "b")).isTrue();
        assertThat(votes.cast(bob, VoteService.Question.MAP, "b")).isTrue();
        assertThat(votes.counts(VoteService.Question.MAP)).containsEntry("b", 2).doesNotContainKey("a");
        assertThat(votes.winner(VoteService.Question.MAP)).contains("b");
    }

    @Test
    @DisplayName("nothing off the ballot, nothing once closed")
    void refused() {
        votes.open(List.of("a", "b"), List.of(), List.of());
        assertThat(votes.cast(amy, VoteService.Question.MAP, "z")).isFalse();
        assertThat(votes.asks(VoteService.Question.TYPE)).as("one option or none is not a question").isFalse();
        votes.close();
        assertThat(votes.cast(amy, VoteService.Question.MAP, "a")).isFalse();
    }
}
