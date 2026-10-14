package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.model.Flag;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrimFlagsTest {

    private final UUID alex = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final List<Flag> flagged = new ArrayList<>();
    private boolean experimental;

    private GrimFlags flags() {
        GrimFlags flags = new GrimFlags(id -> id.equals(alex) ? Optional.of(player) : Optional.empty(),
                (who, task) -> task.run(), (who, flag) -> flagged.add(flag));
        flags.settings(de.raindancer.modules.moderation.ModerationSettings.DEFAULTS
                .withAntiCheatExperimental(experimental));
        return flags;
    }

    @Test
    @DisplayName("a Grim flag lands in its family, with Grim's check name, its details and its level")
    void lands() {
        flags().accept(alex, "Reach", "3.41 blocks", 4.0, false);
        flags().accept(alex, "Simulation", "offset 0.21", 12.5, false);

        assertThat(flagged).extracting(Flag::check).containsExactly(CheckType.GRIM_COMBAT, CheckType.GRIM_MOVEMENT);
        assertThat(flagged.getFirst().detail()).contains("Reach").contains("3.41 blocks").contains("Grim 4");
        assertThat(flagged.getFirst().weight()).isEqualTo(1);
    }

    @Test
    @DisplayName("Grim's experimental checks count only when the owner lets experimental checks count")
    void experimentalChecks() {
        flags().accept(alex, "AimModulo360", "x", 1, true);
        assertThat(flagged).isEmpty();
        experimental = true;
        flags().accept(alex, "AimModulo360", "x", 1, true);
        assertThat(flagged).hasSize(1);
    }

    @Test
    @DisplayName("a player no longer on this server is let go; details too long are cut")
    void edges() {
        flags().accept(UUID.randomUUID(), "Reach", "x", 1, false);
        assertThat(flagged).isEmpty();
        flags().accept(alex, "Simulation", "y".repeat(5000), 1, false);
        assertThat(flagged.getFirst().detail().length()).isLessThan(600);
        flags().accept(alex, null, null, Double.NaN, false);
        assertThat(flagged).hasSize(2);
    }
}
