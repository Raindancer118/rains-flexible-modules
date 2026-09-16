package de.raindancer.modules.worldutils.rules;

import de.raindancer.modules.worldutils.model.Dimension;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;

/**
 * Where {@code /dim} puts somebody, before anything checks the ground.
 *
 * <h2>The numbers, and where they come from</h2>
 * <ul>
 *   <li><b>Overworld and Nether</b> are scaled by each world's own coordinate scale — eight to one in
 *       vanilla, whatever a datapack made it otherwise — so {@code /dim} lands where a portal built on
 *       the same spot would.</li>
 *   <li><b>Into the Nether</b> the height is kept between {@link #NETHER_FLOOR} and
 *       {@link #NETHER_CEILING}. The bedrock roof is at 127, and from 120 the roof was often the
 *       nearest place to stand — the classic way to strand somebody.</li>
 *   <li><b>Into the overworld</b> the arrival looks for the surface rather than the nearest pocket of
 *       air, which from a Nether height is a cave.</li>
 *   <li><b>The End, either way,</b> has no meaningful corresponding place: into it is its arrival
 *       point, and out of it is where the player respawns if that is in the target world, else its
 *       spawn — exactly where vanilla's exit portal sends them.</li>
 *   <li>Everything is kept inside the target world's border. Scaling a Nether position by eight can
 *       easily land outside it.</li>
 * </ul>
 */
public final class LandingRule implements IWorldUtilsRule {

    public static final int NETHER_FLOOR = 32;
    public static final int NETHER_CEILING = 100;

    /** Where to look for ground, and whether the ground wanted is the surface. */
    public record Landing(double x, double y, double z, boolean surface) {
    }

    /** The target world, as far as this rule needs to know it. */
    public record Target(Dimension dimension, double coordinateScale, int minHeight, int maxHeight,
                         double borderCentreX, double borderCentreZ, double borderSize) {
    }

    /**
     * @return where to look, or empty for "the target world's own arrival point" — see the class notes
     */
    public Optional<Landing> between(Dimension from, double fromScale, double x, double y, double z,
                                     Target to) {
        if (from == null || to == null || from == to.dimension()
                || from == Dimension.END || to.dimension() == Dimension.END) {
            return Optional.empty();
        }
        double ratio = (fromScale <= 0 ? 1 : fromScale) / (to.coordinateScale() <= 0 ? 1 : to.coordinateScale());
        double half = Math.max(1, to.borderSize() / 2 - 1);
        double landX = clamp(x * ratio, to.borderCentreX() - half, to.borderCentreX() + half);
        double landZ = clamp(z * ratio, to.borderCentreZ() - half, to.borderCentreZ() + half);
        if (to.dimension() == Dimension.NETHER) {
            return Optional.of(new Landing(landX, clamp(y, NETHER_FLOOR, NETHER_CEILING), landZ, false));
        }
        double landY = clamp(y, to.minHeight() + 1, to.maxHeight() - 2);
        return Optional.of(new Landing(landX, landY, landZ, true));
    }

    /**
     * Where somebody arrives when there is no corresponding place: their own respawn point when it is in
     * {@code target}, the world's spawn otherwise — a bed in another world must not send them there.
     */
    public static Location arrivalPoint(Location respawn, World target) {
        if (respawn != null && respawn.isWorldLoaded() && target.equals(respawn.getWorld())) {
            return respawn;
        }
        return target.getSpawnLocation();
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    @Override
    public String describe() {
        return "where /dim lands somebody: scaled, under the Nether roof, on the surface, inside the border";
    }
}
