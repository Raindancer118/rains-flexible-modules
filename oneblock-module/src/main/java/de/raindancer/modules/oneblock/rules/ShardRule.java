package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.Content;

/** Shards for breaking: an award every n-th break of the island, all its blocks counted together. */
public final class ShardRule implements IOneBlockRule {

    /** What the break that brought the island's count to {@code totalAfter} earns. */
    public long earnedAt(Content content, long totalAfter) {
        return totalAfter > 0 && totalAfter % content.shardEvery() == 0 ? content.shardAmount() : 0;
    }

    /** Everything {@code broken} breaks would have earned — what an island coming over from BentoBox is given. */
    public long earnedOver(Content content, long broken) {
        return broken <= 0 ? 0 : broken / content.shardEvery() * content.shardAmount();
    }

    @Override
    public String describe() {
        return "how many shards breaking earns";
    }
}
