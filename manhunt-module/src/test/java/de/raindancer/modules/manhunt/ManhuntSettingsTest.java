package de.raindancer.modules.manhunt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManhuntSettingsTest {

    @Test
    void defaultsAreThePlainManhuntShape() {
        ManhuntSettings d = ManhuntSettings.DEFAULTS;

        assertThat(d.runnerWin()).isEqualTo(ManhuntSettings.RunnerWinCondition.PORTAL_EXIT);
        assertThat(d.hunterWin()).isEqualTo(ManhuntSettings.HunterWinCondition.ALL_RUNNERS_DEAD);
        assertThat(d.resetOnStart()).isTrue();
        assertThat(d.closeWhitelistOnStart()).isTrue();
        assertThat(d.runnerSelfJoinEnabled()).isTrue();
        assertThat(d.lobbySpawnSet()).isFalse();
        assertThat(d.lobbyWorldName()).isEmpty();
        assertThat(d.lobbyRadius()).isEqualTo(15);
    }

    @Test
    void eachWithMethodChangesOnlyItsOwnComponent() {
        ManhuntSettings changed = ManhuntSettings.DEFAULTS
                .withRunnerWin(ManhuntSettings.RunnerWinCondition.ADVANCEMENT)
                .withHunterWin(ManhuntSettings.HunterWinCondition.TIMEOUT)
                .withHunterTimeoutMinutes(20)
                .withHunterReleaseDelaySeconds(90)
                .withWorldName("hunt")
                .withSeedChoice(ManhuntSettings.SeedChoice.FIXED)
                .withSeedValue(42L)
                .withCloseWhitelistOnStart(false)
                .withChaosCooldownSeconds(30)
                .withResetOnStart(false)
                .withRunnerAdvancementKey("minecraft:adventure/kill_a_mob")
                .withRunnerSelfJoinEnabled(false)
                .withLobbySpawnSet(true)
                .withLobbyWorldName("hunt")
                .withLobbyX(1.5)
                .withLobbyY(64.0)
                .withLobbyZ(-3.5)
                .withLobbyYaw(90.0)
                .withLobbyRadius(30);

        assertThat(changed.runnerWin()).isEqualTo(ManhuntSettings.RunnerWinCondition.ADVANCEMENT);
        assertThat(changed.hunterWin()).isEqualTo(ManhuntSettings.HunterWinCondition.TIMEOUT);
        assertThat(changed.hunterTimeoutMinutes()).isEqualTo(20);
        assertThat(changed.hunterReleaseDelaySeconds()).isEqualTo(90);
        assertThat(changed.worldName()).isEqualTo("hunt");
        assertThat(changed.seedChoice()).isEqualTo(ManhuntSettings.SeedChoice.FIXED);
        assertThat(changed.seedValue()).isEqualTo(42L);
        assertThat(changed.closeWhitelistOnStart()).isFalse();
        assertThat(changed.chaosCooldownSeconds()).isEqualTo(30);
        assertThat(changed.resetOnStart()).isFalse();
        assertThat(changed.runnerAdvancementKey()).isEqualTo("minecraft:adventure/kill_a_mob");
        assertThat(changed.runnerSelfJoinEnabled()).isFalse();
        assertThat(changed.lobbySpawnSet()).isTrue();
        assertThat(changed.lobbyWorldName()).isEqualTo("hunt");
        assertThat(changed.lobbyX()).isEqualTo(1.5);
        assertThat(changed.lobbyY()).isEqualTo(64.0);
        assertThat(changed.lobbyZ()).isEqualTo(-3.5);
        assertThat(changed.lobbyYaw()).isEqualTo(90.0);
        assertThat(changed.lobbyRadius()).isEqualTo(30);

        // Nothing else drifted along with the one component each with… call was for.
        assertThat(ManhuntSettings.DEFAULTS.runnerWin())
                .isEqualTo(ManhuntSettings.RunnerWinCondition.PORTAL_EXIT);
    }

    @Test
    void outOfRangeValuesClampRatherThanExplode() {
        ManhuntSettings tooFar = ManhuntSettings.DEFAULTS
                .withHunterTimeoutMinutes(-5)
                .withHunterReleaseDelaySeconds(-1)
                .withChaosCooldownSeconds(1000);

        assertThat(tooFar.hunterTimeoutMinutesClamped()).isEqualTo(1);
        assertThat(tooFar.hunterReleaseDelaySecondsClamped()).isEqualTo(0);
        assertThat(tooFar.chaosCooldownSecondsClamped()).isEqualTo(300);
    }
}
