package de.raindancer.modules.voicebridge.util;

import java.util.List;

/**
 * 16-bit PCM at 48 kHz, the one rate both sides speak — Discord hands over and takes stereo
 * big-endian bytes, Simple Voice Chat mono {@code short}s, 20 ms at a time on both.
 */
public final class Pcm {

    /** One 20 ms frame of mono at 48 kHz. */
    public static final int FRAME_SAMPLES = 960;

    private Pcm() {
    }

    public static short[] stereoBigEndianToMono(byte[] stereo) {
        short[] mono = new short[stereo.length / 4];
        for (int i = 0; i < mono.length; i++) {
            int at = i * 4;
            int left = (short) ((stereo[at] << 8) | (stereo[at + 1] & 0xFF));
            int right = (short) ((stereo[at + 2] << 8) | (stereo[at + 3] & 0xFF));
            mono[i] = (short) ((left + right) / 2);
        }
        return mono;
    }

    public static byte[] monoToStereoBigEndian(short[] mono) {
        byte[] stereo = new byte[mono.length * 4];
        for (int i = 0; i < mono.length; i++) {
            byte high = (byte) (mono[i] >> 8);
            byte low = (byte) mono[i];
            int at = i * 4;
            stereo[at] = high;
            stereo[at + 1] = low;
            stereo[at + 2] = high;
            stereo[at + 3] = low;
        }
        return stereo;
    }

    /** A scaled copy; the input may be on its way somewhere else as well. */
    public static short[] gain(short[] samples, int percent) {
        short[] scaled = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            scaled[i] = clip((long) samples[i] * percent / 100);
        }
        return scaled;
    }

    /** Summed and clipped; wrapping round instead would turn two loud voices into a crack. */
    public static short[] mix(List<short[]> frames) {
        int length = 0;
        for (short[] frame : frames) {
            length = Math.max(length, frame.length);
        }
        int[] sum = new int[length];
        for (short[] frame : frames) {
            for (int i = 0; i < frame.length; i++) {
                sum[i] += frame[i];
            }
        }
        short[] mixed = new short[length];
        for (int i = 0; i < length; i++) {
            mixed[i] = clip(sum[i]);
        }
        return mixed;
    }

    private static short clip(long sample) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
    }
}
