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
                .withRunnerAdvancementKey("minecraft:adventure/kill_a_mob");

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
