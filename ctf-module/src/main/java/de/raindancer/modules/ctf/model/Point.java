package de.raindancer.modules.ctf.model;

import java.util.Locale;
import java.util.Optional;

/**
 * A spot with a facing. In a map's file it is relative to the map's lowest corner; once the map stands in the world,
 * {@link #plus} makes it the real place.
 */
public record Point(double x, double y, double z, float yaw, float pitch) {

    public static Point of(double x, double y, double z) {
        return new Point(x, y, z, 0f, 0f);
    }

    public Point plus(double dx, double dy, double dz) {
        return new Point(x + dx, y + dy, z + dz, yaw, pitch);
    }

    public Point minus(double dx, double dy, double dz) {
        return plus(-dx, -dy, -dz);
    }

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceSquared(Point other) {
        return distanceSquared(other.x, other.y, other.z);
    }

    /** Flat distance, ignoring height — a base is reached by standing over it, not by being at its exact height. */
    public double flatDistanceSquared(double ox, double oz) {
        double dx = x - ox;
        double dz = z - oz;
        return dx * dx + dz * dz;
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    /** One line in a map file: {@code x y z yaw pitch}. */
    public String line() {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch);
    }

    public static Optional<Point> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 3 && parts.length != 5) {
            return Optional.empty();
        }
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            float yaw = parts.length == 5 ? Float.parseFloat(parts[3]) : 0f;
            float pitch = parts.length == 5 ? Float.parseFloat(parts[4]) : 0f;
            return Optional.of(new Point(x, y, z, yaw, pitch));
        } catch (NumberFormatException garbled) {
            return Optional.empty();
        }
    }
}
