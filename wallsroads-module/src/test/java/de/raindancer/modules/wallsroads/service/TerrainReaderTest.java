package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static de.raindancer.modules.wallsroads.service.TerrainReader.Reading;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the ground actually is — the question the old {@code topSolidY} got wrong, which is why a
 * road through a forest was built over the treetops.
 */
class TerrainReaderTest {

    private static final String WORLD = "world";
    private static final Column HERE = new Column(0, 0);

    private final TerrainReader reader = new TerrainReader();

    /** A world that is stone up to {@code groundY} and air above it. */
    private static FakeGround land(int groundY) {
        FakeGround ground = new FakeGround().fillWith("AIR");
        for (int y = -64; y <= groundY; y++) {
            ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, y, 0), "STONE");
        }
        return ground;
    }

    @Test
    @DisplayName("plain ground reads as the block above the last solid one")
    void findsPlainGround() {
        Reading reading = reader.read(land(70), WORLD, HERE);

        assertThat(reading.groundY()).isEqualTo(71);
        assertThat(reading.isUnderWater()).isFalse();
    }

    @Test
    @DisplayName("a tree is not the ground — the reading is the soil under it")
    void seesThroughATree() {
        FakeGround ground = land(70);
        for (int y = 71; y <= 78; y++) {
            ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, y, 0), "OAK_LOG");
        }
        ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, 79, 0), "OAK_LEAVES");

        assertThat(reader.read(ground, WORLD, HERE).groundY()).isEqualTo(71);
    }

    @Test
    @DisplayName("grass, flowers and snow on top are not the ground either")
    void seesThroughGroundCover() {
        FakeGround ground = land(70);
        ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, 71, 0), "SHORT_GRASS");
        ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, 72, 0), "SNOW");

        assertThat(reader.read(ground, WORLD, HERE).groundY()).isEqualTo(71);
    }

    @Test
    @DisplayName("under water, the sea bed is the ground and the surface is recorded separately")
    void readsWaterAsWater() {
        FakeGround ground = land(40);
        for (int y = 41; y <= 62; y++) {
            ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, y, 0), "WATER");
        }

        Reading reading = reader.read(ground, WORLD, HERE);

        assertThat(reading.groundY()).isEqualTo(41);
        assertThat(reading.isUnderWater()).isTrue();
        assertThat(reading.waterSurfaceY()).isEqualTo(62);
        assertThat(reading.waterDepth()).isEqualTo(22);
    }

    @Test
    @DisplayName("ice counts as water — a road laid on a frozen ocean is a road laid on the sea")
    void treatsIceAsWater() {
        FakeGround ground = land(40);
        for (int y = 41; y <= 61; y++) {
            ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, y, 0), "WATER");
        }
        ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, 62, 0), "ICE");

        assertThat(reader.read(ground, WORLD, HERE).isUnderWater()).isTrue();
    }

    @Test
    @DisplayName("lava is not water, and is not something to lay a road on either")
    void readsLavaAsAGap() {
        FakeGround ground = land(30);
        for (int y = 31; y <= 34; y++) {
            ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, y, 0), "LAVA");
        }

        Reading reading = reader.read(ground, WORLD, HERE);

        assertThat(reading.isUnderWater()).isFalse();
        assertThat(reading.isLava()).isTrue();
        assertThat(reading.groundY()).isEqualTo(31);
    }

    @Test
    @DisplayName("a column with nothing solid at all reads as the world floor rather than throwing")
    void survivesTheVoid() {
        Reading reading = reader.read(new FakeGround().fillWith("AIR"), WORLD, HERE);

        assertThat(reading.groundY()).isEqualTo(reader.floorY());
        assertThat(reading.isVoid()).isTrue();
    }

    @Test
    @DisplayName("a cave under the surface does not become the ground")
    void ignoresCavesBelowTheSurface() {
        FakeGround ground = land(70);
        for (int y = 40; y <= 50; y++) {
            ground.put(new de.raindancer.core.world.safety.Spot(WORLD, 0, y, 0), "CAVE_AIR");
        }

        assertThat(reader.read(ground, WORLD, HERE).groundY()).isEqualTo(71);
    }
}
