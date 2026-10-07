package de.raindancer.modules.voicebridge.service;

import de.raindancer.modules.voicebridge.model.Placement;
import de.raindancer.modules.voicebridge.util.Spatial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalMixTest {

    private static final Spatial.Ear EAR = new Spatial.Ear(0, 64, 0, 0f);

    private final Map<UUID, Spatial.Ear> positions = new HashMap<>();
    private final UUID channelA = UUID.randomUUID();
    private final UUID channelB = UUID.randomUUID();
    private PersonalMix mix;

    @BeforeEach
    void setUp() {
        mix = new PersonalMix(1, 5, 60_000, () -> 0L, id -> Optional.ofNullable(positions.get(id)));
    }

    private static short[] loud() {
        short[] frame = new short[4];
        java.util.Arrays.fill(frame, (short) 10_000);
        return frame;
    }

    private static int left(byte[] stereo, int sample) {
        return (short) ((stereo[sample * 4] << 8) | (stereo[sample * 4 + 1] & 0xFF));
    }

    private static int right(byte[] stereo, int sample) {
        return (short) ((stereo[sample * 4 + 2] << 8) | (stereo[sample * 4 + 3] & 0xFF));
    }

    @Test
    @DisplayName("nothing heard is nothing to send")
    void silence() {
        assertThat(mix.next(EAR)).isNull();
        assertThat(mix.hasAudio()).isFalse();
    }

    @Test
    @DisplayName("a group voice (static) is both ears at full volume")
    void staticIsCentred() {
        mix.offer(channelA, loud(), new Placement.Static());

        byte[] out = mix.next(EAR);

        assertThat(left(out, 0)).isEqualTo(10_000);
        assertThat(right(out, 0)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("a voice at a position fades with distance and pans to its side")
    void positioned() {
        mix.offer(channelA, loud(), new Placement.At(-24, 64, 0, 48));

        byte[] out = mix.next(EAR);

        assertThat(right(out, 0)).as("half the distance, near ear").isEqualTo(5_000);
        assertThat(left(out, 0)).isLessThan(right(out, 0));
    }

    @Test
    @DisplayName("a voice following an entity is placed wherever that entity is now")
    void followsTheEntity() {
        UUID speaker = UUID.randomUUID();
        positions.put(speaker, new Spatial.Ear(0, 64, 12, 0f));
        mix.offer(channelA, loud(), new Placement.Following(speaker, 48));

        byte[] out = mix.next(EAR);

        assertThat(left(out, 0)).isEqualTo(7_500);
        assertThat(right(out, 0)).isEqualTo(7_500);
    }

    @Test
    @DisplayName("an entity nobody knows the position of is played centred rather than dropped")
    void unknownEntity() {
        mix.offer(channelA, loud(), new Placement.Following(UUID.randomUUID(), 48));

        assertThat(left(mix.next(EAR), 0)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("out of range is silent, and two voices add up")
    void rangeAndSum() {
        mix.offer(channelA, loud(), new Placement.At(0, 64, 100, 48));
        mix.offer(channelB, loud(), new Placement.Static());

        byte[] out = mix.next(EAR);

        assertThat(left(out, 0)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("forgetting a source drops its queued audio")
    void forget() {
        mix.offer(channelA, loud(), new Placement.Static());
        mix.forget(channelA);

        assertThat(mix.next(EAR)).isNull();
    }
}
