package de.raindancer.modules.enchants.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Autopilot's flight: straight up like a launch, across high above the land, and a steep dive onto the place, one
 * point a tick. Never faster than {@link #DIVE_SPEED} blocks a tick, so the land ahead can load in time.
 */
public final class Autopilot {

    public enum Phase { LAUNCH, CRUISE, DIVE }

    /** The cinematic cameras, switched in flight. */
    public enum Camera {
        CHASE("Chase"), SIDE("Side"), FRONT("Front"), OVERHEAD("Overhead"), FIRST_PERSON("First person");

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

    private record Segment(double[] from, double[] to, double speed, Phase phase, int ticks) {
    }

    public static final double LAUNCH_SPEED = 1.4;
    public static final double CRUISE_SPEED = 2.2;
    public static final double DIVE_SPEED = 2.6;
    /** How far above the higher of the two ends it cruises. */
    public static final double CLIMB = 40;
    /** How many blocks across a block of dive takes: a dive of about 35 degrees. */
    private static final double DIVE_RUN = 1 / Math.tan(Math.toRadians(35));
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

    /** One whole flight. */
    public static final class Plan {
        private final List<Segment> segments;
        private final int ticks;

        private Plan(List<Segment> segments) {
            this.segments = segments;
            this.ticks = segments.stream().mapToInt(Segment::ticks).sum();
        }

        public int ticks() {
            return ticks;
        }

        /** Where the flier is {@code tick} ticks in: 0 is the start, {@link #ticks()} the place itself. */
        public Point at(int tick) {
            int left = Math.clamp(tick, 0, ticks);
            for (Segment segment : segments) {
                if (left <= segment.ticks()) {
                    return point(segment, (double) left / segment.ticks());
                }
                left -= segment.ticks();
            }
            Segment last = segments.getLast();
            return point(last, 1);
        }

        private static Point point(Segment segment, double fraction) {
            double[] a = segment.from();
            double[] b = segment.to();
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double dz = b[2] - a[2];
            return new Point(a[0] + dx * fraction, a[1] + dy * fraction, a[2] + dz * fraction,
                    yaw(dx, dz), pitch(dx, dy, dz), segment.phase());
        }
    }

    /**
     * @param ceiling the highest the flight may go — a little under the world's build limit
     */
    public static Plan plan(double[] from, double[] to, double ceiling) {
        double cruise = Math.min(Math.max(from[1], to[1]) + CLIMB, ceiling);
        double[] top = {from[0], Math.max(cruise, from[1]), from[2]};
        double dx = to[0] - top[0];
        double dz = to[2] - top[2];
        double across = Math.sqrt(dx * dx + dz * dz);
        double run = Math.max(0, cruise - to[1]) * DIVE_RUN;
        List<Segment> segments = new ArrayList<>();
        segments.add(segment(from, top, LAUNCH_SPEED, Phase.LAUNCH));
        if (across > run && across > 1e-6) {
            double[] edge = {to[0] - dx / across * run, cruise, to[2] - dz / across * run};
            segments.add(segment(top, edge, CRUISE_SPEED, Phase.CRUISE));
            segments.add(segment(edge, to, DIVE_SPEED, Phase.DIVE));
        } else {
            segments.add(segment(top, to, DIVE_SPEED, Phase.DIVE));
        }
        return new Plan(segments);
    }

    private static Segment segment(double[] from, double[] to, double speed, Phase phase) {
        double length = Math.sqrt(Math.pow(to[0] - from[0], 2) + Math.pow(to[1] - from[1], 2) + Math.pow(to[2] - from[2], 2));
        return new Segment(from, to, speed, phase, Math.max(1, (int) Math.ceil(length / speed)));
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
        double length = Math.sqrt(heading[0] * heading[0] + heading[1] * heading[1]);
        double hx = length < 1e-6 ? 0 : heading[0] / length;
        double hz = length < 1e-6 ? 1 : heading[1] / length;
        // Ahead, to the side (the flier's right hand when facing the heading), and up.
        double[] offset = switch (camera) {
            case CHASE -> new double[]{-CAMERA_BACK, 0, CAMERA_UP};
            case SIDE -> new double[]{0, 7, 1};
            case FRONT -> new double[]{9, 0, 1.5};
            case OVERHEAD -> new double[]{-2, 0, 18};
            case FIRST_PERSON -> null;
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
