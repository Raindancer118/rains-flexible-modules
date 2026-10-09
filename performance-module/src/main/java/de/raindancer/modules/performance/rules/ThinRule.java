package de.raindancer.modules.performance.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Which animals go when a crowd is thinned to a number. Named, tamed and leashed animals are
 * somebody's on purpose: they never go, and they count towards those kept. Babies go first, so what
 * is left can still breed.
 */
public final class ThinRule implements IPerformanceRule {

    /** @param kept named, tamed or leashed */
    public record Animal(UUID id, boolean baby, boolean kept) {
    }

    public List<UUID> toRemove(List<Animal> animals, int keep) {
        int over = animals.size() - Math.max(0, keep);
        if (over <= 0) {
            return List.of();
        }
        List<Animal> removable = new ArrayList<>(animals.stream().filter(animal -> !animal.kept()).toList());
        removable.sort(Comparator.comparing((Animal animal) -> !animal.baby()));
        return removable.stream().limit(over).map(Animal::id).toList();
    }

    @Override
    public String describe() {
        return "thins a crowd: babies first, never a named, tamed or leashed animal";
    }
}
