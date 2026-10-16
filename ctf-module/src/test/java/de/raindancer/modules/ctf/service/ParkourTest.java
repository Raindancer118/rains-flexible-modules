package de.raindancer.modules.ctf.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a parkour run counts only the whole way")
class ParkourTest {

    @Test
    @DisplayName("every checkpoint on the way, or the finish does not count")
    void checkpoints() {
        assertThat(LobbyService.mayFinish(new LobbyService.Running(0, 2), 3)).isFalse();
        assertThat(LobbyService.mayFinish(new LobbyService.Running(0, 3), 3)).isTrue();
        assertThat(LobbyService.mayFinish(new LobbyService.Running(0, 0), 0)).as("a course without checkpoints").isTrue();
        assertThat(LobbyService.mayFinish(null, 0)).isFalse();
    }
}
