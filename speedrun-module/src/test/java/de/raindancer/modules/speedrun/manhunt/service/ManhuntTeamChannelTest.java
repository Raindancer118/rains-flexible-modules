package de.raindancer.modules.speedrun.manhunt.service;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.model.ManhuntTeams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Manhunt's team chat")
class ManhuntTeamChannelTest {

    private static final UUID RUNNER_A = UUID.nameUUIDFromBytes("ra".getBytes());
    private static final UUID RUNNER_B = UUID.nameUUIDFromBytes("rb".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("h".getBytes());
    private static final UUID NOBODY = UUID.nameUUIDFromBytes("n".getBytes());

    private final ManhuntTeams teams = new ManhuntTeams(() -> false);
    private final AtomicReference<Hunt> live = new AtomicReference<>();
    private final ManhuntTeamChannel channel =
            new ManhuntTeamChannel(teams, () -> Optional.ofNullable(live.get()));

    @Test
    @DisplayName("during a hunt: your own side, and the tag says which")
    void duringAHunt() {
        live.set(Hunt.of(Set.of(RUNNER_A, RUNNER_B, HUNTER), Set.of(RUNNER_A, RUNNER_B)));

        assertThat(channel.audienceFor(RUNNER_A)).contains(Set.of(RUNNER_A, RUNNER_B));
        assertThat(channel.audienceFor(HUNTER)).contains(Set.of(HUNTER));
        assertThat(channel.tagFor(RUNNER_A)).isEqualTo("[Runners]");
        assertThat(channel.tagFor(HUNTER)).isEqualTo("[Hunters]");
    }

    @Test
    @DisplayName("in the lobby: the side you picked")
    void inTheLobby() {
        teams.joinRunners(RUNNER_A);
        teams.joinRunners(RUNNER_B);
        teams.joinHunters(HUNTER);

        assertThat(channel.audienceFor(RUNNER_B)).contains(Set.of(RUNNER_A, RUNNER_B));
        assertThat(channel.audienceFor(HUNTER)).contains(Set.of(HUNTER));
    }

    @Test
    @DisplayName("somebody on no side has no team chat")
    void noSide() {
        assertThat(channel.audienceFor(NOBODY)).isEmpty();
        live.set(Hunt.of(Set.of(RUNNER_A, HUNTER), Set.of(RUNNER_A)));
        assertThat(channel.audienceFor(NOBODY)).isEmpty();
    }

    @Test
    @DisplayName("it answers to /chat team")
    void id() {
        assertThat(channel.id()).isEqualTo("team");
    }
}
