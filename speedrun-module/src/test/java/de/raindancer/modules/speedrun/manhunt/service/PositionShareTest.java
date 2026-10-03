package de.raindancer.modules.speedrun.manhunt.service;

import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How "/manhunt here" names where you are. */
@DisplayName("/manhunt here")
class PositionShareTest {

    @Test
    @DisplayName("the dimension is named the way players say it")
    void dimensionNames() {
        assertThat(PositionShare.dimension(World.Environment.NORMAL)).isEqualTo("Overworld");
        assertThat(PositionShare.dimension(World.Environment.NETHER)).isEqualTo("Nether");
        assertThat(PositionShare.dimension(World.Environment.THE_END)).isEqualTo("End");
    }
}
