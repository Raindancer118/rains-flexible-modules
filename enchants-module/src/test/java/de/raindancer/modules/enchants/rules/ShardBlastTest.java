package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ShardBlastTest {

    @Test
    @DisplayName("a shard world creeper blows up twice as big, more each kind up, never past twelve")
    void radius() {
        assertThat(ShardBlast.radius(3f, Optional.empty())).isEqualTo(6f);
        assertThat(ShardBlast.radius(3f, Optional.of(ShardTier.SHADOWED))).isEqualTo(6f);
        assertThat(ShardBlast.radius(3f, Optional.of(ShardTier.SHARDBORN))).isEqualTo(7.5f);
        assertThat(ShardBlast.radius(3f, Optional.of(ShardTier.CRYSTAL))).isEqualTo(9f);
        assertThat(ShardBlast.radius(3f, Optional.of(ShardTier.TYRANT))).isEqualTo(10.5f);
        assertThat(ShardBlast.radius(6f, Optional.of(ShardTier.TYRANT))).as("charged").isEqualTo(12f);
    }
}
