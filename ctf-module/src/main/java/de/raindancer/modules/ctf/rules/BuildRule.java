package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Building;
import de.raindancer.modules.ctf.model.Point;

import java.util.List;

/** What a player may place and break during a round. */
public final class BuildRule implements ICtfRule {

    /** The box a map stands in; nothing outside it is anybody's to change. */
    public record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public boolean mayPlace(Building building, int x, int y, int z, Area area, List<Point> keepClear, int radius) {
        return building != Building.NONE && allowedAt(x, y, z, area, keepClear, radius);
    }

    /** @param placedThisRound whether the block was put there during this round */
    public boolean mayBreak(Building building, boolean placedThisRound, int x, int y, int z, Area area,
                            List<Point> keepClear, int radius) {
        return switch (building) {
            case NONE -> false;
            case OWN_BLOCKS -> placedThisRound && allowedAt(x, y, z, area, keepClear, radius);
            case FREE -> allowedAt(x, y, z, area, keepClear, radius);
        };
    }

    public boolean mayBreakForSpleef(int x, int y, int z, Area area, List<Point> keepClear, int radius) {
        return allowedAt(x, y, z, area, keepClear, radius);
    }

    private static boolean allowedAt(int x, int y, int z, Area area, List<Point> keepClear, int radius) {
        if (!area.contains(x, y, z)) {
            return false;
        }
        double most = (double) radius * radius;
        for (Point point : keepClear) {
            if (point.distanceSquared(x + 0.5, y + 0.5, z + 0.5) <= most) {
                return false;
            }
        }
        return true;
    }
}
