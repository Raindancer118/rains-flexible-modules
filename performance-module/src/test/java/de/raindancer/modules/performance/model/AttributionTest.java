package de.raindancer.modules.performance.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AttributionTest {

    @Test
    @DisplayName("shares of the busy time, biggest first; idle samples are not part of the busy time")
    void shares() {
        Attribution attribution = new Attribution();
        for (int i = 0; i < 6; i++) {
            attribution.count(Cause.vanilla(Cause.Area.ENTITIES));
        }
        for (int i = 0; i < 3; i++) {
            attribution.count(Cause.vanilla(Cause.Area.CHUNKS));
        }
        attribution.count(Cause.plugin("RainsAntiCheat"));
        for (int i = 0; i < 10; i++) {
            attribution.count(Cause.vanilla(Cause.Area.IDLE));
        }

        assertThat(attribution.busySamples()).isEqualTo(10);
        assertThat(attribution.ranked()).extracting(Attribution.Share::cause).containsExactly(
                Cause.vanilla(Cause.Area.ENTITIES), Cause.vanilla(Cause.Area.CHUNKS), Cause.plugin("RainsAntiCheat"));
        assertThat(attribution.ranked().getFirst().percent()).isCloseTo(60.0, within(1e-9));
    }

    @Test
    @DisplayName("nothing sampled is nothing to rank")
    void empty() {
        assertThat(new Attribution().ranked()).isEmpty();
    }
}
