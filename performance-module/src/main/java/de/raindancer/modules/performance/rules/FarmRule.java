package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.PerformanceSettings;
import de.raindancer.modules.performance.model.Crowd;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a farm may make one more animal. Only what players farm with is limited — breeding and
 * eggs. Natural spawns have Paper's mob caps, and a spawn egg or a command is somebody meaning it.
 */
public final class FarmRule implements IPerformanceRule {

    public enum Verdict { ALLOW, TOO_MANY_OF_KIND, TOO_MANY_ANIMALS }

    private static final Set<SpawnReason> FARMING = EnumSet.of(SpawnReason.BREEDING, SpawnReason.EGG,
            SpawnReason.DISPENSE_EGG);

    /** Whether a spawn for this reason is limited at all — asked first, so nothing is counted needlessly. */
    public boolean limits(SpawnReason reason, PerformanceSettings settings) {
        return settings.enabled() && FARMING.contains(reason)
                && (settings.mostOfOneKind() > 0 || settings.mostAnimals() > 0);
    }

    public Verdict judge(SpawnReason reason, Crowd near, PerformanceSettings settings) {
        if (!limits(reason, settings)) {
            return Verdict.ALLOW;
        }
        if (settings.mostOfOneKind() > 0 && near.sameKind() >= settings.mostOfOneKind()) {
            return Verdict.TOO_MANY_OF_KIND;
        }
        if (settings.mostAnimals() > 0 && near.animals() >= settings.mostAnimals()) {
            return Verdict.TOO_MANY_ANIMALS;
        }
        return Verdict.ALLOW;
    }

    @Override
    public String describe() {
        return "no more animals are bred or hatched where too many already stand";
    }
}
