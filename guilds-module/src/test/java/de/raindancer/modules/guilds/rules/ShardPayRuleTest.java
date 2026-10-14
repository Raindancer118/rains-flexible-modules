package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.store.ShardBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShardPayRuleTest {

    private final ShardPayRule rule = new ShardPayRule();

    @Test
    @DisplayName("everything carried is paid in, each enchantment only up to what it still needs")
    void uptoWhatIsNeeded() {
        Map<String, Integer> carried = Map.of("rainsenchants:flight", 40, "rainsenchants:smelting", 5);
        Map<String, ShardBook.Progress> progress = Map.of("rainsenchants:flight", new ShardBook.Progress(70, false));

        Map<String, Integer> giving = rule.giving(carried, key -> progress.getOrDefault(key, ShardBook.Progress.NONE), 100);

        assertThat(giving).containsExactlyInAnyOrderEntriesOf(Map.of("rainsenchants:flight", 30, "rainsenchants:smelting", 5));
    }

    @Test
    @DisplayName("nothing for an enchantment the guild unlocked already, or one it has all the shards for")
    void notForWhatIsDone() {
        Map<String, Integer> carried = Map.of("rainsenchants:flight", 10, "rainsenchants:smelting", 10);
        Map<String, ShardBook.Progress> progress = Map.of(
                "rainsenchants:flight", new ShardBook.Progress(100, true),
                "rainsenchants:smelting", new ShardBook.Progress(100, false));

        assertThat(rule.giving(carried, progress::get, 100)).isEmpty();
    }

    @Test
    @DisplayName("shards of nothing carried give nothing")
    void nothingCarried() {
        assertThat(rule.giving(Map.of("rainsenchants:flight", 0), key -> ShardBook.Progress.NONE, 100)).isEmpty();
    }
}
