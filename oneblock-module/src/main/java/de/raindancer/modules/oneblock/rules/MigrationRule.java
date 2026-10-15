package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.BentoIsland;
import de.raindancer.modules.oneblock.model.BlockSpot;
import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandBlock;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Spawn;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a BentoBox island becomes here. Its block keeps its exact coordinates — the island's region files are copied
 * as they are into its own world, so builds, chests and the block all stay where they were. Its breaks pay what they
 * would have earned here, and a dimension AOneBlock had already reached comes unlocked. The money is left undecided,
 * for its owner to choose.
 */
public final class MigrationRule implements IOneBlockRule {

    private static final int REGION_BLOCKS = 512;
    private final ShardRule shards = new ShardRule();

    /** The block counts at which AOneBlock reaches the nether and the end. */
    public record Thresholds(long nether, long end) {
    }

    public Island island(Content content, BentoIsland bento, String ownerName, Thresholds at, UUID newId) {
        Map<IslandDimension, IslandBlock> blocks = new EnumMap<>(IslandDimension.class);
        blocks.put(IslandDimension.OVERWORLD, new IslandBlock(new BlockSpot(bento.x(), bento.y(), bento.z()),
                bento.lifetime(), Map.of()));
        if (bento.lifetime() >= at.nether()) {
            blocks.put(IslandDimension.NETHER, IslandBlock.fresh(IslandDimension.NETHER.start()));
        }
        if (bento.lifetime() >= at.end()) {
            blocks.put(IslandDimension.END, IslandBlock.fresh(IslandDimension.END.start()));
        }
        Map<IslandDimension, Spawn> spawns = new EnumMap<>(IslandDimension.class);
        bento.spawn().ifPresent(spawn -> spawns.put(IslandDimension.OVERWORLD, spawn));
        return new Island(newId, bento.owner(), ownerName, EconomyMode.UNDECIDED, bento.created(),
                shards.earnedOver(content, bento.lifetime()), bento.members(), blocks, spawns, true, Optional.empty());
    }

    /** The region files ({@code r.X.Z.mca}) under the island's protected square. */
    public Set<String> regions(BentoIsland bento) {
        int reach = Math.max(1, bento.protection());
        Set<String> files = new LinkedHashSet<>();
        for (int rx = Math.floorDiv(bento.x() - reach, REGION_BLOCKS); rx <= Math.floorDiv(bento.x() + reach, REGION_BLOCKS); rx++) {
            for (int rz = Math.floorDiv(bento.z() - reach, REGION_BLOCKS); rz <= Math.floorDiv(bento.z() + reach, REGION_BLOCKS); rz++) {
                files.add("r." + rx + "." + rz + ".mca");
            }
        }
        return files;
    }

    /** Whether a spot belongs to this island's square of the grid — where somebody logging out there was. */
    public boolean covers(BentoIsland bento, double x, double z) {
        int range = Math.max(1, bento.range());
        return Math.abs(x - bento.x()) <= range && Math.abs(z - bento.z()) <= range;
    }

    @Override
    public String describe() {
        return "what a BentoBox island becomes here";
    }
}
