package de.raindancer.modules.performance.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SampleRingTest {

    private static final Cause ENTITIES = Cause.vanilla(Cause.Area.ENTITIES);
    private static final Cause PLUGIN = Cause.plugin("LaggyHoppers");

    @Test
    @DisplayName("a spike is explained by exactly the samples taken during that one tick")
    void duringASpike() {
        SampleRing ring = new SampleRing(100);
        ring.add(1_000, ENTITIES);
        ring.add(2_000, PLUGIN);
        ring.add(3_000, PLUGIN);
        ring.add(4_000, ENTITIES);

        Attribution spike = ring.between(1_500, 3_500);

        assertThat(spike.ranked()).extracting(Attribution.Share::cause).containsExactly(PLUGIN);
        assertThat(spike.busySamples()).isEqualTo(2);
    }

    @Test
    @DisplayName("everything it still holds, for a server that is slow all the time")
    void everything() {
        SampleRing ring = new SampleRing(3);
        ring.add(1, PLUGIN);
        ring.add(2, ENTITIES);
        ring.add(3, ENTITIES);
        ring.add(4, ENTITIES);

        assertThat(ring.all().ranked()).extracting(Attribution.Share::cause).containsExactly(ENTITIES);
        assertThat(ring.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("nothing in the window is an empty attribution")
    void nothing() {
        assertThat(new SampleRing(5).between(0, 100).ranked()).isEmpty();
    }
}
