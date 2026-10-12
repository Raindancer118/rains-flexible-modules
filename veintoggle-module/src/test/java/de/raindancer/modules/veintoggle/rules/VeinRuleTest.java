package de.raindancer.modules.veintoggle.rules;

import de.miraculixx.veinminer.VeinMinerEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Which block breaks are Veinminer's extra ones, and which of those to refuse. */
class VeinRuleTest {

    private final VeinRule rule = new VeinRule();
    private final Block block = mock(Block.class);
    private final Player player = mock(Player.class);

    @Test
    @DisplayName("Veinminer's extra blocks are told apart from the block the player broke themselves")
    void tellsThemApart() {
        assertThat(rule.isVeinBlock(new VeinMinerEvent.VeinminerEvent(block, player))).isTrue();
        assertThat(rule.isVeinBlock(new BlockBreakEvent(block, player))).isFalse();
    }

    @Test
    @DisplayName("refused only for a player who switched it off, and never the block they broke by hand")
    void refusesOnlyTheVein() {
        assertThat(rule.refusal(true, false, true)).isEqualTo(VeinRule.Refusal.SWITCHED_OFF);
        assertThat(rule.refusal(true, true, true)).isEqualTo(VeinRule.Refusal.NONE);
        assertThat(rule.refusal(false, false, false)).as("their own break is ordinary mining")
                .isEqualTo(VeinRule.Refusal.NONE);
    }

    @Test
    @DisplayName("without the enchantment on the tool there is no vein, whatever the switch says")
    void needsTheEnchantment() {
        assertThat(rule.refusal(true, true, false)).isEqualTo(VeinRule.Refusal.NO_ENCHANTMENT);
        assertThat(rule.refusal(true, false, false)).as("the missing tool is the first thing to say")
                .isEqualTo(VeinRule.Refusal.NO_ENCHANTMENT);
    }
}
