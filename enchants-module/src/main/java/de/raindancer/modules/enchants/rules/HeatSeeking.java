package de.raindancer.modules.enchants.rules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Heat-seeking: which target a shot was meant for — the one nearest the line it was aimed along — and how the
 * projectile turns towards it, tick by tick, without ever slowing down.
 */
public final class HeatSeeking {

    /** Somebody or something that may be locked on, where its middle is. */
    public record Candidate(UUID id, double x, double y, double z) {
    }

    private HeatSeeking() {
    }

    /** Half the cone's opening around the aim, in degrees. */
    /** How far off the crosshair a precise shot still locks on, in degrees: a near miss, not something else. */
    public static final double PRECISE_CONE = 5;

    /** The cone for a shooter's mode: precise takes what the crosshair is on, wide the level's own cone. */
    public static double cone(int level, boolean precise) {
        return precise ? PRECISE_CONE : cone(level);
    }

    public static double cone(int level) {
        int clamped = Math.max(1, Math.min(3, level));
        return clamped == 3 ? 45 : 2 + 8 * clamped;
    }

    /** How far a target may be, in blocks. */
    public static double range(int level) {
        return 32 + 16 * Math.max(1, Math.min(3, level));
    }

    /** How much of the way to the target's direction the flight turns each tick (0 none, 1 at once). */
    public static double turn(int level) {
        int clamped = Math.max(1, Math.min(3, level));
        return clamped == 3 ? 0.85 : 0.25 + 0.15 * clamped;
    }

    /**
     * The candidate nearest the aim: the smallest angle off {@code look}, inside {@code coneDegrees} and {@code range};
     * of two at the same angle, the nearer.
     */
    /**
     * The same, for one of several shots at once: a creature in {@code taken} — another shot of the volley locked on
     * it already — is only chosen when nothing else is in the cone.
     */
    public static Optional<UUID> pick(double[] eye, double[] look, List<Candidate> candidates, double coneDegrees,
                                      double range, java.util.Set<UUID> taken) {
        List<Candidate> free = candidates.stream().filter(candidate -> !taken.contains(candidate.id())).toList();
        return pick(eye, look, free, coneDegrees, range).or(() -> pick(eye, look, candidates, coneDegrees, range));
    }

    public static Optional<UUID> pick(double[] eye, double[] look, List<Candidate> candidates, double coneDegrees,
                                      double range) {
        double[] aim = unit(look);
        UUID best = null;
        double bestAngle = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (Candidate candidate : candidates) {
            double[] to = {candidate.x() - eye[0], candidate.y() - eye[1], candidate.z() - eye[2]};
            double distance = length(to);
            if (distance < 1.0E-6 || distance > range) {
                continue;
            }
            double cosine = (to[0] * aim[0] + to[1] * aim[1] + to[2] * aim[2]) / distance;
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
            if (angle > coneDegrees) {
                continue;
            }
            if (angle < bestAngle - 1.0E-9 || Math.abs(angle - bestAngle) <= 1.0E-9 && distance < bestDistance) {
                best = candidate.id();
                bestAngle = angle;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * One tick of the flight: the direction turned {@code turn} of the way towards the target, the speed kept and
     * never below {@code minSpeed}.
     */
    public static double[] steer(double[] velocity, double[] from, double[] to, double turn, double minSpeed) {
        double speed = Math.max(minSpeed, length(velocity));
        double[] now = length(velocity) < 1.0E-9 ? unit(new double[]{to[0] - from[0], to[1] - from[1], to[2] - from[2]})
                : unit(velocity);
        double[] wanted = unit(new double[]{to[0] - from[0], to[1] - from[1], to[2] - from[2]});
        double share = Math.max(0, Math.min(1, turn));
        double[] mixed = {now[0] + (wanted[0] - now[0]) * share, now[1] + (wanted[1] - now[1]) * share,
                now[2] + (wanted[2] - now[2]) * share};
        if (length(mixed) < 1.0E-9) {
            mixed = wanted;
        }
        double[] direction = unit(mixed);
        return new double[]{direction[0] * speed, direction[1] * speed, direction[2] * speed};
    }

    private static double length(double[] vector) {
        return Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
    }

    private static double[] unit(double[] vector) {
        double length = length(vector);
        return length < 1.0E-9 ? new double[]{0, 0, 0} : new double[]{vector[0] / length, vector[1] / length,
                vector[2] / length};
    }
}
