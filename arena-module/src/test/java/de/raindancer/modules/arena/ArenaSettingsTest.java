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
        assertThat(defaults.fightMinutes()).isEqualTo(5);
        assertThat(defaults.countdownSeconds()).isEqualTo(5);
        assertThat(defaults.ffaWaitSeconds()).isEqualTo(20);
        assertThat(defaults.sumoRadius()).isEqualTo(6);
        assertThat(defaults.mostStake()).isZero();
        assertThat(defaults.fightSeconds()).isEqualTo(300);
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
        assertThat(defaults.withSumoRadius(1).sumoRadiusClamped()).isEqualTo(3);
        assertThat(defaults.withCountdownSeconds(0).countdownClamped()).isEqualTo(1);
        assertThat(defaults.withFightMinutes(500).fightSeconds()).isEqualTo(3600);
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
