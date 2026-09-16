package de.raindancer.modules.worldutils.model;

import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DimensionTest {

    @Test
    @DisplayName("the vanilla ids and the short forms people type")
    void parsing() {
        assertThat(Dimension.parse("nether")).contains(Dimension.NETHER);
        assertThat(Dimension.parse("THE_END")).contains(Dimension.END);
        assertThat(Dimension.parse("e")).contains(Dimension.END);
        assertThat(Dimension.parse("overworld")).contains(Dimension.OVERWORLD);
        assertThat(Dimension.parse("normal")).contains(Dimension.OVERWORLD);
        assertThat(Dimension.parse("sky")).isEmpty();
        assertThat(Dimension.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("which dimension an environment is, and none for a datapack one")
    void environments() {
        assertThat(Dimension.of(World.Environment.NETHER)).contains(Dimension.NETHER);
        assertThat(Dimension.of(World.Environment.CUSTOM)).isEmpty();
        assertThat(Dimension.END.environment()).isEqualTo(World.Environment.THE_END);
    }

    @Test
    @DisplayName("a world name is what a world key allows, checked before the server is asked")
    void names() {
        assertThat(ManagedWorld.isValidName("farm_2")).isTrue();
        assertThat(ManagedWorld.isValidName("Farm")).isFalse();
        assertThat(ManagedWorld.isValidName("my world")).isFalse();
        assertThat(ManagedWorld.isValidName("")).isFalse();
        assertThat(new ManagedWorld("farm", null).environment()).isEqualTo(World.Environment.NORMAL);
    }
}
