package de.raindancer.modules.voicebridge.util;

/**
 * Hearing the world from one player's head, the way Simple Voice Chat's client does: a linear fade
 * to nothing at the voice distance, and panning that never quite silences the far ear (0.3), flattened
 * when the speaker is far above or below. Written to behave like SVC, so a Discord listener hears the
 * same as somebody with the mod.
 */
public final class Spatial {

    private static final float QUIETER_EAR = 0.3f;
    private static final float PAN_STRENGTH = 1.4f;
    /** Height difference, in blocks, at which panning has flattened out completely. */
    private static final double FLAT_AT_HEIGHT = 32;

    /** Where somebody is listening from: eye position and yaw (Minecraft's: 0 faces +Z, 90 faces -X). */
    public record Ear(double x, double y, double z, float yaw) {
    }

    private Spatial() {
    }

    public static float distanceGain(double distance, double maxDistance) {
        if (maxDistance <= 0) {
            return 0f;
        }
        return (float) (1 - Math.min(distance, maxDistance) / maxDistance);
    }

    public static double distance(Ear ear, double x, double y, double z) {
        double dx = x - ear.x();
        double dy = y - ear.y();
        double dz = z - ear.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** {left, right} volume for a sound at this position, before the distance fade. */
    public static float[] ears(Ear ear, double x, double y, double z) {
        double dx = x - ear.x();
        double dy = y - ear.y();
        double dz = z - ear.z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9) {
            return new float[]{1f, 1f};
        }
        double yaw = Math.toRadians(ear.yaw());
        // The listener's right hand: facing +Z (yaw 0) it points to -X.
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double side = (dx * rightX + dz * rightZ) / length;
        double flatten = 1 - Math.min(1, Math.abs(dy) / FLAT_AT_HEIGHT);
        float pan = (float) (0.5 * side * flatten);

        float left = pan < 0 ? -pan * PAN_STRENGTH + QUIETER_EAR : QUIETER_EAR;
        float right = pan >= 0 ? pan * PAN_STRENGTH + QUIETER_EAR : QUIETER_EAR;
        float fill = 1f - Math.max(left, right);
        return new float[]{left + fill, right + fill};
    }

    /** Adds a mono frame into an interleaved stereo accumulator. */
    public static void addInto(int[] stereo, short[] mono, float left, float right) {
        int samples = Math.min(mono.length, stereo.length / 2);
        for (int i = 0; i < samples; i++) {
            stereo[i * 2] += (int) (mono[i] * left);
            stereo[i * 2 + 1] += (int) (mono[i] * right);
        }
    }

    /** The accumulator as Discord wants it: 16-bit big-endian, clipped. */
    public static byte[] toBigEndian(int[] stereo) {
        byte[] bytes = new byte[stereo.length * 2];
        for (int i = 0; i < stereo.length; i++) {
            int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, stereo[i]));
            bytes[i * 2] = (byte) (sample >> 8);
            bytes[i * 2 + 1] = (byte) sample;
        }
        return bytes;
    }
}
