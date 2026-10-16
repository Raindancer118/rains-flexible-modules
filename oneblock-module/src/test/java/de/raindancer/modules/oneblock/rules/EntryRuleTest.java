package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntryRuleTest {

    private static final UUID OWNER = UUID.randomUUID();
    private final EntryRule rule = new EntryRule();
    private final Island island = Island.create(UUID.randomUUID(), OWNER, "O", EconomyMode.PURE, 0);

    @Test
    @DisplayName("the team goes, a stranger only when invited, staff always")
    void who() {
        UUID stranger = UUID.randomUUID();
        assertThat(rule.judge(island, IslandDimension.OVERWORLD, OWNER, false, false, 0)).isEmpty();
        assertThat(rule.judge(island, IslandDimension.OVERWORLD, stranger, false, false, 0))
                .contains("oneblock.go.not-invited");
        assertThat(rule.judge(island, IslandDimension.OVERWORLD, stranger, true, false, 0)).isEmpty();
        assertThat(rule.judge(island, IslandDimension.OVERWORLD, stranger, false, true, 0)).isEmpty();
    }

    @Test
    @DisplayName("held down keeps everybody but staff out, until the hold runs out")
    void heldDown() {
        Island held = island.withHold(Optional.of(new Hold(Hold.Kind.DOWN, 100, "admin")));
        assertThat(rule.judge(held, IslandDimension.OVERWORLD, OWNER, false, false, 99)).contains("oneblock.go.held-down");
        assertThat(rule.judge(held, IslandDimension.OVERWORLD, OWNER, false, true, 99)).isEmpty();
        assertThat(rule.judge(held, IslandDimension.OVERWORLD, OWNER, false, false, 100)).isEmpty();
    }

    @Test
    @DisplayName("nobody goes to a dimension that is not unlocked, staff neither")
    void locked() {
        assertThat(rule.judge(island, IslandDimension.NETHER, OWNER, false, true, 0)).contains("oneblock.go.locked");
    }
}
