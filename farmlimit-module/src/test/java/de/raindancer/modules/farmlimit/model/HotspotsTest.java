package de.raindancer.modules.farmlimit.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HotspotsTest {

    @Test
    @DisplayName("the most crowded chunks come first, and quiet ones are left out")
    void ranked() {
        Hotspots tally = new Hotspots();
        for (int i = 0; i < 215; i++) {
            tally.count("world", 72, 79, "chicken");
        }
        for (int i = 0; i < 79; i++) {
            tally.count("world", 71, 79, "chicken");
        }
        for (int i = 0; i < 5; i++) {
            tally.count("world", 0, 0, "cow");
        }
        tally.count("world", 71, 79, "item");

        List<Hotspot> top = tally.top(10, 20);

        assertThat(top).extracting(Hotspot::chunkX).containsExactly(72, 71);
        assertThat(top.getFirst().total()).isEqualTo(215);
        assertThat(top.get(1).total()).isEqualTo(80);
        assertThat(top.get(1).mostCommon()).isEqualTo("chicken");
        assertThat(top.get(1).mostCommonCount()).isEqualTo(79);
    }

    @Test
    @DisplayName("the same chunk coordinates in two worlds are two places")
    void worldsAreSeparate() {
        Hotspots tally = new Hotspots();
        tally.count("world", 1, 1, "item");
        tally.count("world_nether", 1, 1, "item");

        assertThat(tally.top(10, 1)).hasSize(2);
    }

    @Test
    @DisplayName("no more than asked for")
    void limited() {
        Hotspots tally = new Hotspots();
        for (int x = 0; x < 30; x++) {
            tally.count("world", x, 0, "pig");
        }
        assertThat(tally.top(10, 1)).hasSize(10);
    }

    @Test
    @DisplayName("a hotspot's block position is the middle of its chunk")
    void middle() {
        Hotspot spot = new Hotspot("world", 72, 79, 215, "chicken", 215);
        assertThat(spot.blockX()).isEqualTo(72 * 16 + 8);
        assertThat(spot.blockZ()).isEqualTo(79 * 16 + 8);
    }
}
