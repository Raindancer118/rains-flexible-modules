package de.raindancer.modules.farmlimit.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A tally of entities per chunk, filled in one pass and then ranked. Not thread-safe: one per count. */
public final class Hotspots {

    private record Place(String world, int x, int z) {
    }

    private final Map<Place, Map<String, Integer>> kinds = new HashMap<>();

    public void count(String world, int chunkX, int chunkZ, String kind) {
        kinds.computeIfAbsent(new Place(world, chunkX, chunkZ), place -> new HashMap<>()).merge(kind, 1, Integer::sum);
    }

    /** The {@code most} most crowded chunks with at least {@code atLeast} entities, busiest first. */
    public List<Hotspot> top(int most, int atLeast) {
        List<Hotspot> spots = new ArrayList<>();
        kinds.forEach((place, byKind) -> {
            int total = byKind.values().stream().mapToInt(Integer::intValue).sum();
            if (total < atLeast) {
                return;
            }
            Map.Entry<String, Integer> common = byKind.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow();
            spots.add(new Hotspot(place.world(), place.x(), place.z(), total, common.getKey(), common.getValue()));
        });
        spots.sort(Comparator.comparingInt(Hotspot::total).reversed());
        return spots.subList(0, Math.min(most, spots.size()));
    }
}
