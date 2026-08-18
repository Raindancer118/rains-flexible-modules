package de.raindancer.modules.speedrun;

import org.bukkit.Location;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The arithmetic {@link SpeedrunCreeperOnBreakListener} and {@link SpeedrunCreeperOnContainerOpenListener}
 * share: roll whether a creeper spawns at a spot at all, and if it does, roll whether it comes out
 * charged. Kept here rather than duplicated twice, since the two listeners' only real difference is
 * what event triggers them and which pair of {@link SpeedrunSettings} chances applies.
 */
final class SpeedrunCreeperHazard {

    private SpeedrunCreeperHazard() {
    }

    /**
     * Rolls {@code spawnChancePercent}; on a hit, spawns a creeper at {@code location} and rolls again
     * for {@code chargedChancePercent}. A miss on the first roll does nothing at all — not even the
     * charged roll runs, so a 0% spawn chance costs nothing beyond the one comparison.
     */
    static void maybeSpawn(Location location, int spawnChancePercent, int chargedChancePercent) {
        if (ThreadLocalRandom.current().nextInt(100) >= spawnChancePercent) {
            return;
        }
        Creeper creeper = (Creeper) location.getWorld().spawnEntity(location, EntityType.CREEPER);
        if (ThreadLocalRandom.current().nextInt(100) < chargedChancePercent) {
            creeper.setPowered(true);
        }
    }
}
