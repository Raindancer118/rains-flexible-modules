package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VolleyTest {

    private static double yawOf(double[] direction) {
        return Math.toDegrees(Math.atan2(-direction[0], direction[2]));
    }

    @Test
    @DisplayName("level I throws two more tridents at 10 degrees either side, level II four, at 10 and 20")
    void fan() {
        double[] north = {0, 0, -1};
        List<double[]> one = Volley.directions(north, 1);
        List<double[]> two = Volley.directions(north, 2);

        assertThat(one).hasSize(2);
        assertThat(two).hasSize(4);
        assertThat(two.stream().mapToDouble(dir -> Math.abs(Math.abs(yawOf(dir)) - 180)).sorted().toArray())
                .containsExactly(new double[]{10, 10, 20, 20}, within(1e-6));
    }

    @Test
    @DisplayName("the fan turns about the vertical: a throw aimed up stays aimed up, every copy as fast")
    void keepsTheAim() {
        double[] upAndNorth = {0, 0.5, -0.8660254037844386};
        for (double[] dir : Volley.directions(upAndNorth, 2)) {
            assertThat(dir[1]).isCloseTo(0.5, within(1e-9));
            assertThat(Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])).isCloseTo(1, within(1e-9));
        }
    }
}
