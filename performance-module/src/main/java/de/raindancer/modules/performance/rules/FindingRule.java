package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.ChunkCensus;
import de.raindancer.modules.performance.model.EntityGroup;
import de.raindancer.modules.performance.model.Finding;
import de.raindancer.modules.performance.model.Fix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Which chunks hold too much, and what may be done. Only what is transient or what the server
 * would remove anyway is offered for removal — items, monsters that despawn, animals over a farm
 * limit. Villagers and hoppers are somebody's build; those are only reported.
 */
public final class FindingRule implements IPerformanceRule {

    /** How much of something in one chunk counts as too much. */
    public record Limits(int itemsPerChunk, int animalsOfOneKind, int monstersPerChunk, int villagersPerChunk,
                         int blockEntitiesOfOneKind, int entitiesPerChunk) {

        public static final Limits DEFAULTS = new Limits(150, 80, 60, 40, 150, 250);
    }

    private final Limits limits;

    public FindingRule(Limits limits) {
        this.limits = limits;
    }

    /**
     * @param farmLimit the farm limit's "most of one kind", or 0 when it is off — what a crowd is
     *                  thinned down to
     */
    public List<Finding> findings(List<ChunkCensus> chunks, int farmLimit) {
        List<Finding> found = new ArrayList<>();
        for (ChunkCensus chunk : chunks) {
            int before = found.size();
            int items = chunk.inGroup(EntityGroup.ITEM);
            if (items >= limits.itemsPerChunk()) {
                found.add(new Finding(Finding.Kind.ITEM_PILE, chunk, chunk.mostCommon(EntityGroup.ITEM).orElse("item"),
                        items, List.of(Fix.clearItems(), Fix.tellOwner())));
            }
            int keep = farmLimit > 0 ? farmLimit : limits.animalsOfOneKind();
            for (Map.Entry<String, Integer> animal : chunk.typesIn(EntityGroup.ANIMAL).entrySet()) {
                if (animal.getValue() >= limits.animalsOfOneKind()) {
                    found.add(new Finding(Finding.Kind.ANIMAL_CROWD, chunk, animal.getKey(), animal.getValue(),
                            List.of(Fix.thin(animal.getKey(), keep), Fix.tellOwner())));
                }
            }
            int monsters = chunk.inGroup(EntityGroup.MONSTER);
            if (monsters >= limits.monstersPerChunk()) {
                found.add(new Finding(Finding.Kind.MONSTER_CROWD, chunk, chunk.mostCommon(EntityGroup.MONSTER).orElse("monster"),
                        monsters, List.of(Fix.clearMonsters(), Fix.tellOwner())));
            }
            int villagers = chunk.inGroup(EntityGroup.VILLAGER);
            if (villagers >= limits.villagersPerChunk()) {
                found.add(new Finding(Finding.Kind.VILLAGER_CROWD, chunk, "villager", villagers, List.of(Fix.tellOwner())));
            }
            chunk.blockEntities().forEach((type, count) -> {
                if (count >= limits.blockEntitiesOfOneKind()) {
                    found.add(new Finding(Finding.Kind.BLOCK_ENTITY_CLUSTER, chunk, type, count, List.of(Fix.tellOwner())));
                }
            });
            if (found.size() == before && chunk.totalEntities() >= limits.entitiesPerChunk()) {
                found.add(new Finding(Finding.Kind.ENTITY_CROWD, chunk, chunk.mostCommon(null).orElse("entity"),
                        chunk.totalEntities(), List.of(Fix.tellOwner())));
            }
        }
        found.sort(Comparator.comparingInt(Finding::count).reversed());
        return found;
    }

    @Override
    public String describe() {
        return "a chunk with " + limits + " or more is a finding";
    }
}
