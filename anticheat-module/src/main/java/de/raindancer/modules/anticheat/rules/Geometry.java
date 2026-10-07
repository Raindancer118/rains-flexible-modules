package de.raindancer.modules.anticheat.rules;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Points, boxes and look directions. Pure arithmetic over Bukkit's value types. */
public final class Geometry {

    private Geometry() {
    }

    /** The unit vector a yaw and pitch look along, the same way Bukkit's Location#getDirection works it out. */
    public static Vector direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double xz = Math.cos(pitchRad);
        return new Vector(-xz * Math.sin(yawRad), -Math.sin(pitchRad), xz * Math.cos(yawRad));
    }

    /** Shortest distance from a point to a box; 0 inside it. */
    public static double distance(Vector point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether a ray from {@code origin} along {@code direction} meets the box within {@code reach}. */
    public static boolean rayHits(Vector origin, Vector direction, BoundingBox box, double reach) {
        if (box.contains(origin)) {
            return true;
        }
        return box.rayTrace(origin, direction, reach) != null;
    }

    /** Degrees between where somebody looks and the direction to a point. */
    public static double angleTo(Vector origin, Vector look, Vector point) {
        Vector to = point.clone().subtract(origin);
        if (to.lengthSquared() < 1e-12) {
            return 0;
        }
        double cos = look.clone().normalize().dot(to.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** The difference between two yaws, wrapped into -180..180. */
    public static double yawDelta(double from, double to) {
        double delta = (to - from) % 360;
        if (delta > 180) {
            delta -= 360;
        } else if (delta < -180) {
            delta += 360;
        }
        return delta;
    }
}
