package de.raindancer.modules.performance.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Everything counted in one chunk: entities by type and group, block entities by type. */
public final class ChunkCensus {

    private final String world;
    private final int chunkX;
    private final int chunkZ;
    private final Map<String, Integer> entities = new LinkedHashMap<>();
    private final Map<String, EntityGroup> groups = new LinkedHashMap<>();
    private final Map<String, Integer> blockEntities = new LinkedHashMap<>();
    /** Where one of each type stands — a claim may cover only some heights, so the chunk's middle is not enough. */
    private final Map<String, double[]> spots = new LinkedHashMap<>();

    public ChunkCensus(String world, int chunkX, int chunkZ) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public void entities(String type, EntityGroup group, int count) {
        entities.merge(type, count, Integer::sum);
        groups.put(type, group);
    }

    /** One entity of {@code type} counted, standing at x y z. */
    public void seen(String type, EntityGroup group, double x, double y, double z) {
        entities(type, group, 1);
        spots.putIfAbsent(type, new double[]{x, y, z});
    }

    /** Where one {@code type} stood, or the middle of the chunk at sea level. */
    public double[] spotOf(String type) {
        double[] spot = spots.get(type);
        return spot != null ? spot.clone() : new double[]{blockX() + 0.5, 64, blockZ() + 0.5};
    }

    public void blockEntities(String type, int count) {
        blockEntities.merge(type, count, Integer::sum);
    }

    public String world() {
        return world;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int blockX() {
        return (chunkX << 4) + 8;
    }

    public int blockZ() {
        return (chunkZ << 4) + 8;
    }

    public int totalEntities() {
        return entities.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int inGroup(EntityGroup group) {
        return entities.entrySet().stream().filter(each -> groups.get(each.getKey()) == group)
                .mapToInt(Map.Entry::getValue).sum();
    }

    /** Each type of a group with how many there are. */
    public Map<String, Integer> typesIn(EntityGroup group) {
        Map<String, Integer> types = new LinkedHashMap<>();
        entities.forEach((type, count) -> {
            if (groups.get(type) == group) {
                types.put(type, count);
            }
        });
        return types;
    }

    /** The most common type of a group, or of everything when {@code group} is null. */
    public Optional<String> mostCommon(EntityGroup group) {
        return entities.entrySet().stream()
                .filter(each -> group == null || groups.get(each.getKey()) == group)
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey);
    }

    public Map<String, Integer> blockEntities() {
        return Map.copyOf(blockEntities);
    }

    public Map<EntityGroup, Integer> byGroup() {
        Map<EntityGroup, Integer> sums = new EnumMap<>(EntityGroup.class);
        entities.forEach((type, count) -> sums.merge(groups.get(type), count, Integer::sum));
        return sums;
    }
}
