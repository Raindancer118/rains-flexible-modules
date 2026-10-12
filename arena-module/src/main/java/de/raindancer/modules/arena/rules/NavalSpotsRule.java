package de.raindancer.modules.arena.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where each boat starts in a naval battle: evenly round a ring through the pit's two spawns — two boats at exactly
 * those spawns — each moved to the nearest water cell nobody else has, and turned to face the middle.
 */
public final class NavalSpotsRule implements IArenaRule {

    /** A block column and the way the boat faces. */
    public record Spot(int x, int z, float yaw) {
    }

    /** How far from the box's edge the ring keeps, so nobody starts against a wall. */
    private static final int MARGIN = 3;

    public List<Spot> spots(int count, double ax, double az, double bx, double bz, Set<BasinRule.Cell> water,
                            int minX, int maxX, int minZ, int maxZ) {
        if (count <= 0 || water.isEmpty()) {
            return List.of();
        }
        double cx = (ax + bx) / 2;
        double cz = (az + bz) / 2;
        double along = Math.hypot(bx - ax, bz - az) / 2;
        double ux = along == 0 ? 1 : (bx - ax) / (2 * along);
        double uz = along == 0 ? 0 : (bz - az) / (2 * along);
        // Across: as wide as the box allows, never wider than along.
        double room = Math.min(Math.abs(-uz) > Math.abs(ux) ? (maxX - minX) / 2.0 : (maxZ - minZ) / 2.0, along) - MARGIN;
        double across = Math.max(1, Math.min(along, room));
        int y = water.iterator().next().y();
        Set<String> taken = new HashSet<>();
        List<Spot> spots = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            double angle = Math.PI + 2 * Math.PI * k / count;
            double px = cx + ux * Math.cos(angle) * along - uz * Math.sin(angle) * across;
            double pz = cz + uz * Math.cos(angle) * along + ux * Math.sin(angle) * across;
            BasinRule.Cell best = null;
            double bestDistance = Double.MAX_VALUE;
            for (BasinRule.Cell cell : water) {
                if (cell.y() != y || taken.contains(cell.x() + "," + cell.z())) {
                    continue;
                }
                double distance = Math.pow(cell.x() + 0.5 - px, 2) + Math.pow(cell.z() + 0.5 - pz, 2);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = cell;
                }
            }
            if (best == null) {
                break;
            }
            taken.add(best.x() + "," + best.z());
            double dx = cx - (best.x() + 0.5);
            double dz = cz - (best.z() + 0.5);
            spots.add(new Spot(best.x(), best.z(), (float) Math.toDegrees(-Math.atan2(dx, dz))));
        }
        return List.copyOf(spots);
    }

    @Override
    public String describe() {
        return "where each boat starts in a naval battle";
    }
}
