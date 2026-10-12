package de.raindancer.modules.anticheat.model;

import java.util.Locale;
import java.util.Optional;

/**
 * One moment of a player's movement, as the checks saw it — and how they stood: the hotbar slot in hand
 * ({@code -1} when not known), sneaking, gliding.
 */
public record ReplayFrame(long atMillis, String world, double x, double y, double z, float yaw, float pitch,
                          boolean onGround, int slot, boolean sneaking, boolean gliding) {

    /** Where they were, without how they stood. */
    public ReplayFrame(long atMillis, String world, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this(atMillis, world, x, y, z, yaw, pitch, onGround, -1, false, false);
    }

    public String encode() {
        return String.format(Locale.ROOT, "%d;%s;%.3f;%.3f;%.3f;%.2f;%.2f;%b;%d;%b;%b", atMillis, world, x, y, z, yaw, pitch,
                onGround, slot, sneaking, gliding);
    }

    public static Optional<ReplayFrame> decode(String line) {
        try {
            String[] p = line.split(";");
            if (p.length != 8 && p.length != 11) {
                return Optional.empty();
            }
            boolean stance = p.length == 11;
            return Optional.of(new ReplayFrame(Long.parseLong(p[0]), p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Double.parseDouble(p[4]), Float.parseFloat(p[5]), Float.parseFloat(p[6]), Boolean.parseBoolean(p[7]),
                    stance ? Integer.parseInt(p[8]) : -1, stance && Boolean.parseBoolean(p[9]),
                    stance && Boolean.parseBoolean(p[10])));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
