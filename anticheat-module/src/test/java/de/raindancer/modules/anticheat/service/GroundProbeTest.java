package de.raindancer.modules.anticheat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lilly's SMP, 11.10.2026: a player sneaking along a wall at y 79.0 (the wall's top block at y 78) was flagged 172
 * times in seven seconds for hovering over air while the client said it stood on the ground. Vanilla's sneak
 * back-off lets the box hang over the edge by anything above 1e-7, and the old probe shrank the box by 0.001.
 */
class GroundProbeTest {

    /** A solid unit block, intersected the way the game does: touching is not colliding. */
    private record Block(int x, int y, int z) {

        boolean hit(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return minX < x + 1 && maxX > x && minY < y + 1 && maxY > y && minZ < z + 1 && maxZ > z;
        }
    }

    private static Surroundings.Collisions world(Block... blocks) {
        List<Block> solid = List.of(blocks);
        return (minX, minY, minZ, maxX, maxY, maxZ) -> solid.stream().anyMatch(b -> b.hit(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static final Surroundings.Collisions WALL = world(new Block(3146, 78, 100));

    @Test
    @DisplayName("standing on a block with the middle of the box over it is ground")
    void onTheBlock() {
        assertThat(Surroundings.groundUnder(WALL, 3146.5, 79.0, 100.5, 0.6)).isTrue();
    }

    @Test
    @DisplayName("hanging over the edge by a sliver is still ground: 0.0004 of overlap, the vanilla sneak back-off")
    void sneakEdge() {
        // The box's low x edge is 3146.9996: 0.0004 of it is over the block, which ends at 3147.
        assertThat(Surroundings.groundUnder(WALL, 3147.2996, 79.0, 100.5, 0.6)).isTrue();
        assertThat(Surroundings.groundUnder(WALL, 3147.29999, 79.0, 100.5, 0.6)).isTrue();
        assertThat(Surroundings.groundUnder(WALL, 100.5, 79.0, 3147.2996, 0.6)).as("the other way round, on a block that is not there").isFalse();
    }

    @Test
    @DisplayName("touching the edge without overlapping it is not ground, nor is a hair under the game's own epsilon")
    void justOff() {
        assertThat(Surroundings.groundUnder(WALL, 3147.3, 79.0, 100.5, 0.6)).isFalse();
        assertThat(Surroundings.groundUnder(WALL, 3147.30000005, 79.0, 100.5, 0.6)).isFalse();
        assertThat(Surroundings.groundUnder(WALL, 3147.5, 79.0, 100.5, 0.6)).isFalse();
    }

    @Test
    @DisplayName("floating clearly above the block is not ground, and sinking in a little still is")
    void height() {
        assertThat(Surroundings.groundUnder(WALL, 3146.5, 79.2, 100.5, 0.6)).isFalse();
        assertThat(Surroundings.groundUnder(WALL, 3146.5, 79.01, 100.5, 0.6)).isTrue();
        assertThat(Surroundings.groundUnder(WALL, 3146.5, 78.98, 100.5, 0.6)).isTrue();
    }

    @Test
    @DisplayName("the corner: overlap on both axes tiny, still ground")
    void corner() {
        Surroundings.Collisions corner = world(new Block(10, 78, 10));
        assertThat(Surroundings.groundUnder(corner, 11.2996, 79.0, 11.2996, 0.6)).isTrue();
    }
}
