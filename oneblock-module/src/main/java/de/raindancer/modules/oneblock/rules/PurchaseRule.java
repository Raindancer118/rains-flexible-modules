package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Upgrade;

import java.util.Optional;
import java.util.UUID;

/**
 * What the island may buy with its shards, and for how many. Anybody on the team may level an upgrade — shards are
 * the team's — but only the owner opens a new dimension, which is the bigger decision.
 */
public final class PurchaseRule implements IOneBlockRule {

    /** A price, or why not. */
    public record Verdict(long price, Optional<String> refusal) {

        public static Verdict price(long price) {
            return new Verdict(price, Optional.empty());
        }

        public static Verdict refused(String key) {
            return new Verdict(0, Optional.of(key));
        }

        public boolean allowed() {
            return refusal.isEmpty();
        }
    }

    public Verdict upgrade(Content content, Island island, UUID buyer, IslandDimension dimension, String id) {
        if (!island.isMember(buyer)) {
            return Verdict.refused("oneblock.buy.not-yours");
        }
        Optional<Upgrade> upgrade = content.upgrade(id);
        if (upgrade.isEmpty()) {
            return Verdict.refused("oneblock.buy.unknown");
        }
        if (!upgrade.get().allowedIn(dimension)) {
            return Verdict.refused("oneblock.buy.wrong-dimension");
        }
        if (!island.unlocked(dimension)) {
            return Verdict.refused("oneblock.buy.locked");
        }
        int level = island.block(dimension).map(block -> block.level(id)).orElse(0);
        Optional<Long> price = upgrade.get().priceOfNext(level);
        if (price.isEmpty()) {
            return Verdict.refused("oneblock.buy.maxed");
        }
        return island.shards() < price.get() ? Verdict.refused("oneblock.buy.too-poor") : Verdict.price(price.get());
    }

    public Verdict unlock(Content content, Island island, UUID buyer, IslandDimension dimension) {
        if (island.unlocked(dimension)) {
            return Verdict.refused("oneblock.unlock.already");
        }
        Optional<Long> price = content.unlockPrice(dimension);
        if (price.isEmpty()) {
            return Verdict.refused("oneblock.unlock.not-for-sale");
        }
        if (!island.owner().equals(buyer)) {
            return Verdict.refused("oneblock.unlock.owner-only");
        }
        return island.shards() < price.get() ? Verdict.refused("oneblock.buy.too-poor") : Verdict.price(price.get());
    }

    @Override
    public String describe() {
        return "what an island may buy with its shards";
    }
}
