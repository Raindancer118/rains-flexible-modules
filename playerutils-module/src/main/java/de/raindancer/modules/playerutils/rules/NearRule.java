package de.raindancer.modules.playerutils.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Who is within a radius, how far, and which way relative to where the viewer is looking. */
public final class NearRule implements IPlayerUtilsRule {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /** Where somebody stands and faces. Yaw as Minecraft has it: 0 south, 90 west, 180 north, -90 east. */
    public record Spot(String name, String world, double x, double y, double z, float yaw) {
    }

    public record Nearby(String name, int distance, String arrow, int heightDifference) {
    }

    public List<Nearby> near(Spot viewer, List<Spot> others, int radius) {
        List<Nearby> found = new ArrayList<>();
        for (Spot other : others) {
            if (!other.world().equals(viewer.world())) {
                continue;
            }
            double dx = other.x() - viewer.x();
            double dz = other.z() - viewer.z();
            double dy = other.y() - viewer.y();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= radius) {
                found.add(new Nearby(other.name(), (int) Math.round(distance), arrow(viewer, other),
                        (int) Math.round(dy)));
            }
        }
        found.sort(Comparator.comparingInt(Nearby::distance).thenComparing(Nearby::name));
        return found;
    }

    public String arrow(Spot viewer, Spot other) {
        double dx = other.x() - viewer.x();
        double dz = other.z() - viewer.z();
        // The yaw a player would have looking straight at the other one, in Minecraft's convention.
        double towards = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = ((towards - viewer.yaw()) % 360 + 360 + 22.5) % 360;
        return ARROWS[(int) (relative / 45) % 8];
    }

    public int radius(int asked, int most) {
        return Math.max(1, Math.min(asked, Math.max(1, most)));
    }

    @Override
    public String describe() {
        return "who is near, how far and which way";
    }
}
