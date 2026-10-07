package de.raindancer.modules.voicebridge.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PcmTest {

    @Test
    @DisplayName("Discord's 20 ms of stereo big-endian becomes 960 mono samples, left and right averaged")
    void stereoToMono() {
        byte[] stereo = new byte[Pcm.FRAME_SAMPLES * 4];
        // first sample: left 1000, right 3000 -> 2000; second: left -2, right -4 -> -3
        put(stereo, 0, (short) 1000);
        put(stereo, 2, (short) 3000);
        put(stereo, 4, (short) -2);
        put(stereo, 6, (short) -4);

        short[] mono = Pcm.stereoBigEndianToMono(stereo);

        assertThat(mono).hasSize(Pcm.FRAME_SAMPLES);
        assertThat(mono[0]).isEqualTo((short) 2000);
        assertThat(mono[1]).isEqualTo((short) -3);
        assertThat(mono[2]).isZero();
    }

    @Test
    @DisplayName("two full-scale channels average without overflowing")
    void stereoToMonoDoesNotOverflow() {
        byte[] stereo = new byte[4];
        put(stereo, 0, Short.MAX_VALUE);
        put(stereo, 2, Short.MAX_VALUE);

        assertThat(Pcm.stereoBigEndianToMono(stereo)[0]).isEqualTo(Short.MAX_VALUE);
    }

    @Test
    @DisplayName("mono goes to Discord as the same sample on both channels, big-endian")
    void monoToStereo() {
        byte[] stereo = Pcm.monoToStereoBigEndian(new short[]{0x1234, -1});

        assertThat(stereo).containsExactly(0x12, 0x34, 0x12, 0x34, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    @Test
    @DisplayName("a round trip through both conversions gives the samples back")
    void roundTrip() {
        short[] mono = {0, 1, -1, 12345, -32768, 32767};

        assertThat(Pcm.stereoBigEndianToMono(Pcm.monoToStereoBigEndian(mono))).containsExactly(mono);
    }

    @Test
    @DisplayName("gain scales, clips at full scale rather than wrapping, and 100 % is the same samples")
    void gain() {
        short[] samples = {1000, -1000, 30000, -30000};

        assertThat(Pcm.gain(samples, 50)).containsExactly((short) 500, (short) -500, (short) 15000, (short) -15000);
        assertThat(Pcm.gain(samples, 200)).containsExactly((short) 2000, (short) -2000, Short.MAX_VALUE, Short.MIN_VALUE);
        assertThat(Pcm.gain(samples, 100)).containsExactly(samples);
        assertThat(Pcm.gain(samples, 0)).containsOnly((short) 0);
    }

    @Test
    @DisplayName("gain never changes the frame it was given — the same frame may go to several places")
    void gainCopies() {
        short[] samples = {1000};
        Pcm.gain(samples, 50);

        assertThat(samples[0]).isEqualTo((short) 1000);
    }

    @Test
    @DisplayName("mixing adds speakers together and clips instead of wrapping into a crack")
    void mix() {
        short[] a = {100, 30000, -30000};
        short[] b = {50, 30000, -30000};

        assertThat(Pcm.mix(List.of(a, b))).containsExactly((short) 150, Short.MAX_VALUE, Short.MIN_VALUE);
    }

    @Test
    @DisplayName("a shorter frame is mixed in as if padded with silence")
    void mixUnevenLengths() {
        assertThat(Pcm.mix(List.of(new short[]{1, 2, 3}, new short[]{10})))
                .containsExactly((short) 11, (short) 2, (short) 3);
    }

    @Test
    @DisplayName("one speaker is passed through as a copy, not the same array")
    void mixOne() {
        short[] only = {7, 8};
        short[] mixed = Pcm.mix(List.of(only));

        assertThat(mixed).containsExactly(only);
        assertThat(mixed).isNotSameAs(only);
    }

    private static void put(byte[] into, int at, short sample) {
        into[at] = (byte) (sample >> 8);
        into[at + 1] = (byte) sample;
    }
}
