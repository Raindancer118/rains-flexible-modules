package de.raindancer.modules.speedrun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a fresh install of this module actually does to a server before anybody configures it.
 *
 * <h2>Why the creeper defaults are a test and not just a number</h2>
 * They shipped at 100% on both block breaks and container opens, so installing the module silently
 * turned every plain speedrun into a hazard run: mine a block, get a creeper, every single time. That
 * is not a default anybody chose — it is the old on/off toggle's "on" surviving the move to a
 * percentage. A default that changes the game for people who never asked for the feature is worth a
 * line in a test, because the next person adding a hazard will copy the shape of the last one.
 */
class SpeedrunSettingsTest {

    @Test
    @DisplayName("the creeper hazard is off until a host turns it on")
    void hazardIsOffByDefault() {
        SpeedrunSettings defaults = SpeedrunSettings.DEFAULTS;

        assertThat(defaults.creeperSpawnChanceOnBreakPercent()).isZero();
        assertThat(defaults.creeperSpawnChanceOnContainerPercent()).isZero();
        assertThat(defaults.chargedCreeperChanceOnBreakPercent()).isZero();
        assertThat(defaults.chargedCreeperChanceOnContainerPercent()).isZero();
    }

    @Test
    @DisplayName("a fresh install still races the dragon, and still needs the exit portal")
    void theRaceItselfIsUnchanged() {
        SpeedrunSettings defaults = SpeedrunSettings.DEFAULTS;

        assertThat(defaults.advancementKey()).isEqualTo(SpeedrunSettings.DRAGON_KILL_ADVANCEMENT);
        assertThat(defaults.requireExitPortalAfterDragon()).isTrue();
        assertThat(defaults.deathPolicy()).isEqualTo(SpeedrunDeathPolicy.OFF);
        assertThat(defaults.worldName()).isEqualTo(SpeedrunSettings.DEFAULT_WORLD_NAME);
    }

    @Test
    @DisplayName("a fresh lobby is safe, staff-started, and resets itself for another run")
    void theLobbyItselfIsSafeByDefault() {
        SpeedrunSettings defaults = SpeedrunSettings.DEFAULTS;

        assertThat(defaults.startBlockStaffOnly()).isTrue();
        assertThat(defaults.lobbyProtected()).isTrue();
        assertThat(defaults.lobbyExplosionsBlocked()).isTrue();
        assertThat(defaults.showTimerToOnlookers()).isTrue();
        assertThat(defaults.restartWhenRunEnds()).isTrue();
        assertThat(defaults.restartAfterSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("a run starts in the morning unless a host says otherwise")
    void theClockIsSetOnStart() {
        SpeedrunSettings defaults = SpeedrunSettings.DEFAULTS;

        assertThat(defaults.setTimeOnStart()).isTrue();
        assertThat(defaults.startTimeTicks()).isEqualTo((int) SpeedrunPreparation.DAY_START);
        assertThat(defaults.timeAtStart()).isEqualTo(SpeedrunPreparation.DAY_START);
    }

    @Test
    @DisplayName("turning the clock setting off means 'leave the world's time alone'")
    void theClockCanBeLeftAlone() {
        SpeedrunSettings off = new SpeedrunSettings(
                "", "world", "", SpeedrunDeathPolicy.OFF, false, 0, 0, 0, 0,
                false, 0, 0, 0, 0, 0,
                true, true, true, true, true, 10, false, 1000,
                true, true, true, true, true, true, true, true, true, true,
                false, false);

        assertThat(off.timeAtStart()).isEqualTo(SpeedrunPreparation.LEAVE_THE_TIME_ALONE);
    }
}
