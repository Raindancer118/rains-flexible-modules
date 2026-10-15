package de.raindancer.modules.enchants.rules;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Autopilot's flying, a tick at a time: straight up at first, then across low over the land — a little above the
 * highest of it ahead, so it climbs before a mountain and comes down again behind it — and at the end a dive onto
 * the place. It never goes into the land: when a climb comes too late it stops and climbs where it is. A place under
 * a roof is landed on from above, on the roof.
 */
public final class AutopilotPilot {

    /** Where one can stand in a column — the first free block above the land — or empty while it is not loaded. */
    @FunctionalInterface
    public interface Terrain {
        OptionalInt top(int x, int z);
    }

    public static final double CRUISE_SPEED = 2.2;
    public static final double LAUNCH_SPEED = 1.4;
    public static final double CLIMB = 1.6;
    public static final double SINK = 1.6;
    public static final double DIVE_SINK = 2.4;
    /** The most a tick ever moves, across and down at once. */
    public static final double FASTEST = Math.hypot(CRUISE_SPEED, DIVE_SINK);
    /** How high above the land ahead it flies. */
    public static final double CLEARANCE = 10;
    /** How far ahead it looks at the land, in blocks. */
    public static final double LOOKAHEAD = 100;
    private static final double SAMPLE = 4;
    /** How the clearance shrinks near the end: a dive of about 35 degrees. */
    private static final double SLOPE = Math.tan(Math.toRadians(35));
    private static final double LAUNCH_RISE = 6;

    private final double[] target;
    private final double ceiling;
    private final double startY;
    private final double dirX;
    private final double dirZ;
    private double x;
    private double y;
    private double z;
    private boolean launching = true;
    private boolean landed;
    private Double landY;

    public AutopilotPilot(double[] from, double[] to, double ceiling) {
        this.target = to.clone();
        this.ceiling = ceiling;
        this.x = from[0];
        this.y = from[1];
        this.z = from[2];
        this.startY = from[1];
        double across = Math.hypot(to[0] - from[0], to[2] - from[2]);
        this.dirX = across < 1e-9 ? 0 : (to[0] - from[0]) / across;
        this.dirZ = across < 1e-9 ? 1 : (to[2] - from[2]) / across;
    }

    public boolean landed() {
        return landed;
    }

    private double left() {
        return Math.hypot(target[0] - x, target[2] - z);
    }

    private static int block(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    /**
     * One tick on.
     *
     * @return where the flier is now; empty when the land ahead is not loaded yet and it waits where it is
     */
    public Optional<Autopilot.Point> step(Terrain terrain) {
        if (landed) {
            return Optional.of(point(0, 0, Autopilot.Phase.DIVE));
        }
        if (landY == null) {
            OptionalInt there = terrain.top(block(target[0]), block(target[2]));
            if (there.isEmpty()) {
                return Optional.empty();
            }
            landY = Math.max(target[1], there.getAsInt());
        }
        double left = left();
        double highest = Double.NEGATIVE_INFINITY;
        double ahead = Math.min(LOOKAHEAD, left);
        for (double along = 0; ; along = Math.min(ahead, along + SAMPLE)) {
            OptionalInt top = terrain.top(block(x + dirX * along), block(z + dirZ * along));
            if (top.isEmpty()) {
                return Optional.empty();
            }
            highest = Math.max(highest, top.getAsInt());
            if (along >= ahead) {
                break;
            }
        }
        double clearance = Math.min(CLEARANCE, Math.max(1, left * SLOPE));
        double wanted = Math.min(ceiling, left <= CRUISE_SPEED ? landY : Math.max(highest + clearance, landY));
        if (launching) {
            double goal = Math.min(ceiling, Math.max(wanted, startY + LAUNCH_RISE));
            double rise = Math.min(LAUNCH_SPEED, goal - y);
            y += Math.max(0, rise);
            if (y >= goal - 1e-9) {
                launching = false;
            }
            return Optional.of(point(0, Math.max(0, rise), Autopilot.Phase.LAUNCH));
        }
        boolean diving = left * SLOPE < CLEARANCE;
        double down = diving ? DIVE_SINK : SINK;
        double dy = Math.clamp(wanted - y, -down, CLIMB);
        double across = Math.min(CRUISE_SPEED, left);
        double nx = x + dirX * across;
        double nz = z + dirZ * across;
        double ny = y + dy;
        OptionalInt below = terrain.top(block(nx), block(nz));
        if (below.isEmpty()) {
            return Optional.empty();
        }
        boolean atTheEnd = across >= left - 1e-9;
        double floor = atTheEnd ? landY : below.getAsInt();
        if (ny < floor - 1e-9) {
            // Too low for the next block across: climb where it is first.
            across = 0;
            nx = x;
            nz = z;
            dy = Math.min(CLIMB, Math.max(0, floor - y));
            ny = y + dy;
        }
        x = nx;
        y = ny;
        z = nz;
        if (left() < 1e-9 && Math.abs(y - landY) < 1e-9) {
            landed = true;
        }
        return Optional.of(point(across, dy, diving ? Autopilot.Phase.DIVE : Autopilot.Phase.CRUISE));
    }

    private Autopilot.Point point(double across, double dy, Autopilot.Phase phase) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
        float pitch = across < 1e-9 && Math.abs(dy) < 1e-9 ? 0 : (float) Math.toDegrees(-Math.atan2(dy, across));
        return new Autopilot.Point(x, y, z, yaw, pitch, phase);
    }
}
