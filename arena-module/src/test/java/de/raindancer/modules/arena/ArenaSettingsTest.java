package de.raindancer.modules.arena;

import org.bukkit.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArenaSettingsTest {

    private final ArenaSettings defaults = ArenaSettings.DEFAULTS;

    @Test
    @DisplayName("every default spelled out")
    void defaults() {
        assertThat(defaults.lobbyServer()).isEqualTo("lillyxoxo");
        assertThat(defaults.borderMargin()).isEqualTo(6);
        assertThat(defaults.gameMode()).isEqualTo(GameMode.ADVENTURE);
        assertThat(defaults.hubFed()).isTrue();
    }

    @Test
    @DisplayName("nonsense in the file is clamped or replaced rather than believed")
    void clamped() {
        assertThat(defaults.withBorderMargin(-3).marginClamped()).isZero();
        assertThat(defaults.withBorderMargin(500).marginClamped()).isEqualTo(64);
        assertThat(defaults.withGameMode(GameMode.SPECTATOR).gameModeOrDefault())
                .as("spectators cannot fight").isEqualTo(GameMode.ADVENTURE);
        assertThat(defaults.withGameMode(null).gameModeOrDefault()).isEqualTo(GameMode.ADVENTURE);
        assertThat(defaults.withLobbyServer(" ").lobbyOrDefault()).isEqualTo("lobby");
        assertThat(defaults.withLobbyServer(" smp ").lobbyOrDefault()).isEqualTo("smp");
    }

    @Test
    @DisplayName("every with… changes its own component and nothing else")
    void withMethods() {
        assertThat(defaults.withHubFed(false).hubFed()).isFalse();
        assertThat(defaults.withHubFed(false).borderMargin()).isEqualTo(defaults.borderMargin());
        assertThat(defaults.withLobbyServer("x").gameMode()).isEqualTo(defaults.gameMode());
        assertThat(defaults.withGameMode(GameMode.SURVIVAL).lobbyServer()).isEqualTo(defaults.lobbyServer());
    }
}
