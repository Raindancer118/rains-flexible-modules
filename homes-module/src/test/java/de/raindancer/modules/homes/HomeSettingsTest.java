package de.raindancer.modules.homes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one field {@code SetHome} had that {@code RainsHomes} did not until it was migrated:
 * {@link HomeSettings#playSound()}.
 */
class HomeSettingsTest {

    @Test
    @DisplayName("play-sound defaults on, like safe arrival and bringing what you lead")
    void defaultsOn() {
        assertThat(HomeSettings.DEFAULTS.playSound()).isTrue();
    }

    @Test
    @DisplayName("withPlaySound changes only that one field")
    void withPlaySoundChangesOnlyThatField() {
        HomeSettings off = HomeSettings.DEFAULTS.withPlaySound(false);

        assertThat(off.playSound()).isFalse();
        assertThat(off.max()).isEqualTo(HomeSettings.DEFAULTS.max());
        assertThat(off.cooldownSeconds()).isEqualTo(HomeSettings.DEFAULTS.cooldownSeconds());
        assertThat(off.cancelOnMove()).isEqualTo(HomeSettings.DEFAULTS.cancelOnMove());
        assertThat(off.warmupSeconds()).isEqualTo(HomeSettings.DEFAULTS.warmupSeconds());
        assertThat(off.safeArrival()).isEqualTo(HomeSettings.DEFAULTS.safeArrival());
        assertThat(off.bringWhatYouLead()).isEqualTo(HomeSettings.DEFAULTS.bringWhatYouLead());
    }

    @Test
    @DisplayName("every other wither leaves play-sound untouched")
    void otherWithersLeavePlaySoundAlone() {
        HomeSettings changed = HomeSettings.DEFAULTS
                .withMax(10)
                .withCooldownSeconds(30)
                .withCancelOnMove(true)
                .withWarmupSeconds(5)
                .withSafeArrival(false)
                .withBringWhatYouLead(false)
                .withAllowCrossWorld(false)
                .withOperatorsBypass(true);

        assertThat(changed.playSound()).isEqualTo(HomeSettings.DEFAULTS.playSound());
    }

    @Test
    @DisplayName("every money setting defaults to nothing, so a server needs no economy")
    void moneyIsOffByDefault() {
        HomeSettings defaults = HomeSettings.DEFAULTS;
        assertThat(defaults.slotPrice()).isEqualTo("0");
        assertThat(defaults.slotPriceGrowthPercent()).isZero();
        assertThat(defaults.mostBoughtSlots()).isZero();
        assertThat(defaults.teleportPrice()).isEqualTo("0");
    }

    @Test
    @DisplayName("the money withers change only their own field")
    void moneyWithers() {
        HomeSettings changed = HomeSettings.DEFAULTS.withSlotPrice("5").withSlotPriceGrowthPercent(10)
                .withMostBoughtSlots(4).withTeleportPrice("2");
        assertThat(changed.slotPrice()).isEqualTo("5");
        assertThat(changed.slotPriceGrowthPercent()).isEqualTo(10);
        assertThat(changed.mostBoughtSlots()).isEqualTo(4);
        assertThat(changed.teleportPrice()).isEqualTo("2");
        assertThat(changed.max()).isEqualTo(HomeSettings.DEFAULTS.max());
        assertThat(changed.withMax(9).slotPrice()).isEqualTo("5");
        assertThat(changed.withPlaySound(false).teleportPrice()).isEqualTo("2");
    }
}
