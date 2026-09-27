package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.model.Hunt;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Who hears "/manhunt here": everybody. */
@DisplayName("/manhunt here")
class PositionShareTest {

    private static final UUID RUNNER_A = UUID.nameUUIDFromBytes("ra".getBytes());
    private static final UUID RUNNER_B = UUID.nameUUIDFromBytes("rb".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("h".getBytes());
    private static final UUID BYSTANDER = UUID.nameUUIDFromBytes("by".getBytes());
    private static final Set<UUID> ONLINE = Set.of(RUNNER_A, RUNNER_B, HUNTER, BYSTANDER);

    private final Hunt hunt = Hunt.of(Set.of(RUNNER_A, RUNNER_B, HUNTER), Set.of(RUNNER_A, RUNNER_B));

    @Test
    @DisplayName("everybody online is told — Runners, Hunters and bystanders alike")
    void everybody() {
        assertThat(PositionShare.audience(Optional.of(hunt), RUNNER_A, ONLINE)).isEqualTo(ONLINE);
        assertThat(PositionShare.audience(Optional.empty(), HUNTER, ONLINE)).isEqualTo(ONLINE);
    }

    @Test
    @DisplayName("the dimension is named the way players say it")
    void dimensionNames() {
        assertThat(PositionShare.dimension(World.Environment.NORMAL)).isEqualTo("Overworld");
        assertThat(PositionShare.dimension(World.Environment.NETHER)).isEqualTo("Nether");
        assertThat(PositionShare.dimension(World.Environment.THE_END)).isEqualTo("End");
    }
}
