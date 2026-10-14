package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PortalFrameTest {

    /** Blocks are frame where listed, open air everywhere inside {@code air}, and solid stone otherwise. */
    private record Grid(Set<String> frame, Set<String> air) implements PortalFrame.Grid {
        @Override
        public boolean frame(int x, int y, int z) {
            return frame.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean open(int x, int y, int z) {
            return air.contains(x + "," + y + "," + z);
        }
    }

    /** A frame along x from (x0, y0) with an inside of {@code width} by {@code height}, at z = 0. */
    private static Grid frameAlongX(int x0, int y0, int width, int height, boolean gap) {
        Set<String> frame = new HashSet<>();
        Set<String> air = new HashSet<>();
        for (int x = x0 - 1; x <= x0 + width; x++) {
            frame.add(x + "," + (y0 - 1) + ",0");
            frame.add(x + "," + (y0 + height) + ",0");
        }
        for (int y = y0; y < y0 + height; y++) {
            frame.add((x0 - 1) + "," + y + ",0");
            frame.add((x0 + width) + "," + y + ",0");
            for (int x = x0; x < x0 + width; x++) {
                air.add(x + "," + y + ",0");
            }
        }
        if (gap) {
            frame.remove((x0 + width) + "," + (y0 + 1) + ",0");
            air.add((x0 + width) + "," + (y0 + 1) + ",0");
        }
        return new Grid(frame, air);
    }

    @Test
    @DisplayName("a nether-portal-sized frame is found from any block inside it, along the axis it stands on")
    void found() {
        Grid grid = frameAlongX(0, 64, 2, 3, false);
        Optional<PortalFrame.Inside> inside = PortalFrame.find(grid, 1, 66, 0);
        assertThat(inside).isPresent();
        assertThat(inside.get().alongX()).isTrue();
        assertThat(inside.get().blocks()).hasSize(6).contains(new int[]{0, 64, 0}, new int[]{1, 66, 0});
    }

    @Test
    @DisplayName("a frame with a hole, or one too small, is no portal")
    void refused() {
        assertThat(PortalFrame.find(frameAlongX(0, 64, 2, 3, true), 0, 64, 0)).isEmpty();
        assertThat(PortalFrame.find(frameAlongX(0, 64, 1, 3, false), 0, 64, 0)).isEmpty();
        assertThat(PortalFrame.find(frameAlongX(0, 64, 2, 2, false), 0, 64, 0)).isEmpty();
    }

    @Test
    @DisplayName("a big one up to 21 by 21 works too")
    void big() {
        assertThat(PortalFrame.find(frameAlongX(10, 70, 21, 21, false), 20, 80, 0)).hasValueSatisfying(
                inside -> assertThat(inside.blocks()).hasSize(21 * 21));
        assertThat(PortalFrame.find(frameAlongX(10, 70, 22, 3, false), 20, 70, 0)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the blocks a player touches are those their hitbox overlaps, not just the one at their feet")
    void touched() {
        // Stepping into a portal at z 0 from the side: feet still over z -1, the hitbox already reaching into z 0.
        java.util.List<int[]> touched = PortalFrame.touched(0.5, 65.0, -0.2, 0.3, 1.8);

        assertThat(touched).anyMatch(block -> block[0] == 0 && block[1] == 65 && block[2] == 0);
        assertThat(touched).anyMatch(block -> block[0] == 0 && block[1] == 66 && block[2] == 0);
        assertThat(touched).noneMatch(block -> block[2] == 1);
        assertThat(touched).noneMatch(block -> block[1] == 67);
    }
}
