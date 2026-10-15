package de.raindancer.modules.enchants.rules;


/**
 * Autopilot's flight: straight up like a launch, across high above the land, and a steep dive onto the place, one
 * point a tick. Never faster than {@link #DIVE_SPEED} blocks a tick, so the land ahead can load in time.
 */
public final class Autopilot {

    public enum Phase { LAUNCH, CRUISE, DIVE }

    /** The cinematic cameras, switched in flight. */
    public enum Camera {
        ORBIT("Orbit"), CHASE("Chase"), SIDE("Side"), FRONT("Front"), OVERHEAD("Overhead"), FIRST_PERSON("First person");

        private final String title;

        Camera(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public Camera next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Camera previous() {
            return values()[(ordinal() + values().length - 1) % values().length];
        }
    }

    /** Where the flier is in a tick, and which way it faces (the game's yaw and pitch). */
    public record Point(double x, double y, double z, float yaw, float pitch, Phase phase) {
    }

    public static final double ORBIT_RADIUS = 7;
    /** Degrees the orbit camera goes round each tick: once round in six seconds. */
    public static final double ORBIT_STEP = 3;
    private static final double CAMERA_BACK = 8;
    private static final double CAMERA_UP = 3;

    private Autopilot() {
    }

    /** Seconds before the elytra flies again: the server's setting at I, half at II, none at III. */
    public static int cooldownSeconds(int level, int setting) {
        return switch (Math.clamp(level, 1, 3)) {
            case 1 -> setting;
            case 2 -> setting / 2;
            default -> 0;
        };
    }

    /** The orbit camera's tilt while it circles by itself, and how far the mouse may tilt it. */
    public static final double ORBIT_PITCH = 15;
    public static final double ORBIT_HIGHEST = 80;
    public static final double ORBIT_LOWEST = -40;

    /** A pitch the orbit camera may take. */
    public static double orbitPitch(double pitch) {
        return Math.clamp(pitch, ORBIT_LOWEST, ORBIT_HIGHEST);
    }

    /**
     * The orbit camera looking along {@code yaw} and {@code pitch} (the game's) at the flier, from
     * {@link #ORBIT_RADIUS} away — as a third-person camera does, so the mouse can turn it.
     */
    public static Point orbit(Point flier, double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        return new Point(flier.x() - lookX * ORBIT_RADIUS, flier.y() + 1 - lookY * ORBIT_RADIUS,
                flier.z() - lookZ * ORBIT_RADIUS, (float) yaw, (float) pitch, flier.phase());
    }

    /** The chase camera: behind the flier along {@code heading} (x z), above it, looking at it. */
    public static Point camera(Point flier, double[] heading) {
        return camera(flier, heading, Camera.CHASE).orElseThrow();
    }

    /**
     * Where a camera hangs for the flier, looking at it; empty for the first-person one, which looks through the
     * flier's own eyes.
     */
    public static java.util.Optional<Point> camera(Point flier, double[] heading, Camera camera) {
        return camera(flier, heading, camera, 0);
    }

    /** The same, {@code tick} ticks into the flight — which only the orbit camera, going round, minds. */
    public static java.util.Optional<Point> camera(Point flier, double[] heading, Camera camera, int tick) {
        if (camera == Camera.ORBIT) {
            return java.util.Optional.of(orbit(flier, tick * ORBIT_STEP, ORBIT_PITCH));
        }
        double length = Math.sqrt(heading[0] * heading[0] + heading[1] * heading[1]);
        double hx = length < 1e-6 ? 0 : heading[0] / length;
        double hz = length < 1e-6 ? 1 : heading[1] / length;
        // Ahead, to the side (the flier's right hand when facing the heading), and up.
        double[] offset = switch (camera) {
            case CHASE -> new double[]{-CAMERA_BACK, 0, CAMERA_UP};
            case SIDE -> new double[]{0, 7, 1};
            case FRONT -> new double[]{9, 0, 1.5};
            case OVERHEAD -> new double[]{-2, 0, 18};
            case FIRST_PERSON, ORBIT -> null;
        };
        if (offset == null) {
            return java.util.Optional.empty();
        }
        double sx = -hz;
        double sz = hx;
        double x = flier.x() + hx * offset[0] + sx * offset[1];
        double y = flier.y() + offset[2];
        double z = flier.z() + hz * offset[0] + sz * offset[1];
        double dx = flier.x() - x;
        double dy = flier.y() + 1 - y;
        double dz = flier.z() - z;
        return java.util.Optional.of(new Point(x, y, z, yaw(dx, dz), pitch(dx, dy, dz), flier.phase()));
    }

    private static float yaw(double dx, double dz) {
        return Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9 ? 0 : (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static float pitch(double dx, double dy, double dz) {
        return (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
}
