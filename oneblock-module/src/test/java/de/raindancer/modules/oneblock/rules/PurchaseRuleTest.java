package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseRuleTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private final PurchaseRule rule = new PurchaseRule();

    private static Island island(long shards) {
        return Island.create(UUID.randomUUID(), OWNER, "O", EconomyMode.PURE, 0).withMember(MEMBER).withShardsAdded(shards);
    }

    @Test
    @DisplayName("an upgrade's next level, for anybody on the team who can pay for it")
    void upgrade() {
        assertThat(rule.upgrade(Contents.content(), island(20), MEMBER, IslandDimension.OVERWORLD, "coal"))
                .isEqualTo(PurchaseRule.Verdict.price(20));
        assertThat(rule.upgrade(Contents.content(), island(19), MEMBER, IslandDimension.OVERWORLD, "coal").refusal())
                .contains("oneblock.buy.too-poor");
        assertThat(rule.upgrade(Contents.content(), island(500), UUID.randomUUID(), IslandDimension.OVERWORLD, "coal")
                .refusal()).contains("oneblock.buy.not-yours");
    }

    @Test
    @DisplayName("no upgrade past the top, nor one this dimension does not take, nor one nobody knows")
    void notForSale() {
        Island maxed = island(500).withBlock(IslandDimension.OVERWORLD, block -> block.withLevel("coal", 2));
        assertThat(rule.upgrade(Contents.content(), maxed, OWNER, IslandDimension.OVERWORLD, "coal").refusal())
                .contains("oneblock.buy.maxed");
        assertThat(rule.upgrade(Contents.content(), island(500), OWNER, IslandDimension.OVERWORLD, "quartz").refusal())
                .contains("oneblock.buy.wrong-dimension");
        assertThat(rule.upgrade(Contents.content(), island(500), OWNER, IslandDimension.OVERWORLD, "nope").refusal())
                .contains("oneblock.buy.unknown");
        assertThat(rule.upgrade(Contents.content(), island(500), OWNER, IslandDimension.NETHER, "quartz").refusal())
                .contains("oneblock.buy.locked");
    }

    @Test
    @DisplayName("unlocking: only the owner, once, and only what is for sale")
    void unlock() {
        assertThat(rule.unlock(Contents.content(), island(300), OWNER, IslandDimension.NETHER))
                .isEqualTo(PurchaseRule.Verdict.price(300));
        assertThat(rule.unlock(Contents.content(), island(300), MEMBER, IslandDimension.NETHER).refusal())
                .contains("oneblock.unlock.owner-only");
        assertThat(rule.unlock(Contents.content(), island(299), OWNER, IslandDimension.NETHER).refusal())
                .contains("oneblock.buy.too-poor");
        assertThat(rule.unlock(Contents.content(), island(9999), OWNER, IslandDimension.OVERWORLD).refusal())
                .contains("oneblock.unlock.already");
        Island unlocked = island(9999).unlocking(IslandDimension.NETHER).orElseThrow();
        assertThat(rule.unlock(Contents.content(), unlocked, OWNER, IslandDimension.NETHER).refusal())
                .contains("oneblock.unlock.already");
    }
}
