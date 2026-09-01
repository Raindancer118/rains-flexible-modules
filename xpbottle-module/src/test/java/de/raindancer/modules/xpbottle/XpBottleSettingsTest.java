package de.raindancer.modules.xpbottle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The sums that turn a base and a step into a tier. */
class XpBottleSettingsTest {

    private final XpBottleSettings defaults = XpBottleSettings.DEFAULTS;

    @Test
    @DisplayName("tier zero is the plain bottle's capacity, not the siphon's base")
    void tierZeroIsThePlainBottle() {
        assertThat(defaults.capacityFor(0)).isEqualTo(defaults.plainCapacity());
        assertThat(defaults.reachFor(0)).isZero();
    }

    @Test
    @DisplayName("each tier adds the step, so tier three is the base plus twice it")
    void tiersStepUp() {
        assertThat(defaults.capacityFor(1)).isEqualTo(500);
        assertThat(defaults.capacityFor(2)).isEqualTo(1000);
        assertThat(defaults.capacityFor(3)).isEqualTo(1500);
        assertThat(defaults.reachFor(1)).isEqualTo(4);
        assertThat(defaults.reachFor(3)).isEqualTo(8);
    }

    @Test
    @DisplayName("a tier past the highest is read as the highest, not as a bigger one")
    void tiersAboveTheHighestAreClamped() {
        assertThat(defaults.capacityFor(9)).isEqualTo(defaults.capacityFor(3));
        assertThat(defaults.reachFor(9)).isEqualTo(defaults.reachFor(3));
    }

    @Test
    @DisplayName("the reach never runs past what somebody can see into")
    void theReachIsCapped() {
        XpBottleSettings greedy = defaults.withSiphonReachBase(30).withSiphonReachPerTier(16)
                .withHighestTier(10);

        assertThat(greedy.reachFor(10)).isEqualTo(XpBottleSettings.MOST_REACH);
    }

    @Test
    @DisplayName("a capacity that would overflow an int is capped rather than wrapping negative")
    void capacityDoesNotOverflow() {
        XpBottleSettings absurd = defaults.withSiphonCapacityBase(1_000_000)
                .withSiphonCapacityPerTier(1_000_000).withHighestTier(10);

        assertThat(absurd.capacityFor(10)).isPositive();
    }

    @Test
    @DisplayName("the per-second draw is spread over the timer's period, and never rounds to nothing")
    void theDrawRateIsSpreadOverThePeriod() {
        assertThat(defaults.withSiphonPointsPerSecond(200).pointsPerTimerRun(4)).isEqualTo(40);
        assertThat(defaults.withSiphonPointsPerSecond(20).pointsPerTimerRun(20)).isEqualTo(20);
        assertThat(defaults.withSiphonPointsPerSecond(1).pointsPerTimerRun(1))
                .as("a slow setting still draws something rather than silently drawing nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("nonsense in the file is clamped rather than believed")
    void nonsenseIsClamped() {
        XpBottleSettings broken = defaults.withPlainCapacity(-10).withHighestTier(0)
                .withSiphonCapacityBase(0);

        assertThat(broken.capacityFor(0)).isEqualTo(1);
        assertThat(broken.highestTierClamped()).isEqualTo(1);
        assertThat(broken.capacityFor(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("every with… changes its own component and nothing else")
    void withMethodsChangeOneThing() {
        assertThat(defaults.withPlainBottlesWork(false).plainBottlesWork()).isFalse();
        assertThat(defaults.withPlainBottlesWork(false).plainCapacity())
                .isEqualTo(defaults.plainCapacity());
        assertThat(defaults.withFillCooldownSeconds(30).fillCooldownSeconds()).isEqualTo(30);
        assertThat(defaults.withFillCooldownSeconds(30).siphonPointsPerSecond())
                .isEqualTo(defaults.siphonPointsPerSecond());
    }
}
