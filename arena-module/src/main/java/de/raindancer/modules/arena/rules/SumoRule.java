package de.raindancer.modules.arena.rules;

/** Sumo: whoever is pushed further than the ring's radius from its middle, or below its floor, is out. */
public final class SumoRule implements IArenaRule {

    public boolean isOut(double dx, double dz, double dropBelowFloor, double radius) {
        return dx * dx + dz * dz > radius * radius || dropBelowFloor > 1.5;
    }

    /** Where a fighter starts, and the way they look. */
    public record Start(double x, double z, float yaw) {
    }

    /**
     * The two starts: either side of the ring's middle at half its radius, along the line through the pit's spawns,
     * facing each other — a pit's own spawns are usually far outside any ring.
     */
    public java.util.List<Start> starts(double cx, double cz, double ax, double az, double bx, double bz, double radius) {
        double length = Math.hypot(bx - ax, bz - az);
        double ux = length == 0 ? 1 : (bx - ax) / length;
        double uz = length == 0 ? 0 : (bz - az) / length;
        double half = radius / 2;
        Start west = new Start(cx - ux * half, cz - uz * half, yawTowards(-ux * half, -uz * half));
        Start east = new Start(cx + ux * half, cz + uz * half, yawTowards(ux * half, uz * half));
        return java.util.List.of(west, east);
    }

    /** The yaw of somebody at (centre + offset) looking at the centre. */
    private static float yawTowards(double offsetX, double offsetZ) {
        return (float) Math.toDegrees(-Math.atan2(-offsetX, -offsetZ));
    }

    @Override
    public String describe() {
        return "out of the ring is out";
    }
}
