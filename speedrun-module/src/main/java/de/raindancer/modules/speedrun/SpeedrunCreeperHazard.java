package de.raindancer.modules.speedrun;

import org.bukkit.Location;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The one bit of arithmetic {@link SpeedrunCreeperOnBreakListener} and
 * {@link SpeedrunCreeperOnContainerOpenListener} share: spawn a creeper at a spot, and roll whether
 * it comes out charged. Kept here rather than duplicated twice, since the two listeners' only real
 * difference is what event triggers them and which of {@link SpeedrunSettings}'s two chances applies.
 */
final class SpeedrunCreeperHazard {

    private SpeedrunCreeperHazard() {
    }

    /** Spawns a creeper at {@code location}, charged with probability {@code chargedChancePercent}. */
    static void spawn(Location location, int chargedChancePercent) {
        Creeper creeper = (Creeper) location.getWorld().spawnEntity(location, EntityType.CREEPER);
        if (ThreadLocalRandom.current().nextInt(100) < chargedChancePercent) {
            creeper.setPowered(true);
        }
    }
}
