package de.raindancer.modules.anticheat.model;

import java.util.Locale;
import java.util.Optional;

/** One moment of a player's movement, as the checks saw it. */
public record ReplayFrame(long atMillis, String world, double x, double y, double z, float yaw, float pitch,
                          boolean onGround) {

    public String encode() {
        return String.format(Locale.ROOT, "%d;%s;%.3f;%.3f;%.3f;%.2f;%.2f;%b", atMillis, world, x, y, z, yaw, pitch, onGround);
    }

    public static Optional<ReplayFrame> decode(String line) {
        try {
            String[] p = line.split(";");
            if (p.length != 8) {
                return Optional.empty();
            }
            return Optional.of(new ReplayFrame(Long.parseLong(p[0]), p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Double.parseDouble(p[4]), Float.parseFloat(p[5]), Float.parseFloat(p[6]), Boolean.parseBoolean(p[7])));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
