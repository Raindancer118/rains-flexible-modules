package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.IslandBlock;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Upgrade;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Map;

/**
 * What a broken one block comes back as. Every installed block or mob upgrade of the dimension takes part in one
 * shared roll, so two 5% upgrades mix in 10% of the breaks rather than 9.75%; a roll that hits none of them picks from
 * the dimension's pool by weight. A mob leaves the block to the pool and spawns on top.
 */
public final class GeneratorRule implements IOneBlockRule {

    private static final double FULL = 100.0;

    /** @param mob spawned on top of the block; null for none */
    public record Outcome(Material material, EntityType mob) {
    }

    /**
     * @param roll 0 (inclusive) to 100 (exclusive): the shared roll the upgrades share
     * @param pick 0 (inclusive) to 1 (exclusive): where in the pool's weight a miss lands
     */
    public Outcome next(Content content, IslandDimension dimension, IslandBlock block, double roll, double pick) {
        double passed = 0;
        for (Map.Entry<String, Integer> installed : block.upgrades().entrySet()) {
            Upgrade upgrade = content.upgrade(installed.getKey()).orElse(null);
            if (upgrade == null || !upgrade.kind().rolls() || !upgrade.allowedIn(dimension)) {
                continue;
            }
            passed = Math.min(FULL, passed + chanceOf(upgrade, installed.getValue()));
            if (roll < passed) {
                return upgrade.kind() == Upgrade.Kind.MOB
                        ? new Outcome(fromPool(content, dimension, pick), upgrade.mob())
                        : new Outcome(upgrade.block(), null);
            }
        }
        return new Outcome(fromPool(content, dimension, pick), null);
    }

    /** What all of the dimension's rolling upgrades add up to, at most a hundred. */
    public double rollingTotal(Content content, IslandDimension dimension, IslandBlock block) {
        double total = 0;
        for (Map.Entry<String, Integer> installed : block.upgrades().entrySet()) {
            Upgrade upgrade = content.upgrade(installed.getKey()).orElse(null);
            if (upgrade != null && upgrade.kind().rolls() && upgrade.allowedIn(dimension)) {
                total += chanceOf(upgrade, installed.getValue());
            }
        }
        return Math.min(FULL, total);
    }

    /** A special's chance on this block: its level's, at most a hundred. Zero where it does not apply. */
    public double specialChance(Content content, IslandDimension dimension, IslandBlock block, Upgrade.Kind kind) {
        double total = 0;
        for (Map.Entry<String, Integer> installed : block.upgrades().entrySet()) {
            Upgrade upgrade = content.upgrade(installed.getKey()).orElse(null);
            if (upgrade != null && upgrade.kind() == kind && upgrade.allowedIn(dimension)) {
                total += chanceOf(upgrade, installed.getValue());
            }
        }
        return Math.min(FULL, total);
    }

    /**
     * The chance at a level. A level past the top counts as the top: an owner shortening an upgrade in the file must
     * not take away what an island already paid for.
     */
    public double chanceOf(Upgrade upgrade, int level) {
        return upgrade.chanceAt(Math.min(level, upgrade.maxLevel()));
    }

    private static Material fromPool(Content content, IslandDimension dimension, double pick) {
        List<Content.Weighted> pool = content.pool(dimension);
        int total = pool.stream().mapToInt(Content.Weighted::weight).sum();
        if (total <= 0) {
            return dimension.icon() == Material.GRASS_BLOCK ? Material.DIRT : dimension.icon();
        }
        double target = Math.clamp(pick, 0.0, 0.999_999) * total;
        int seen = 0;
        for (Content.Weighted weighted : pool) {
            seen += weighted.weight();
            if (target < seen) {
                return weighted.material();
            }
        }
        return pool.getLast().material();
    }

    @Override
    public String describe() {
        return "what the one block turns into after a break";
    }
}
