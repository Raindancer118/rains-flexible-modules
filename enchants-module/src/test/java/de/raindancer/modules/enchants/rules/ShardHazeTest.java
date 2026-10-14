package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ShardHazeTest {

    @Test
    @DisplayName("as many specks as asked, all around the player within the radius, from the feet to well above the head")
    void around() {
        List<double[]> specks = ShardHaze.specks(new SplittableRandom(3), 50, 12.0);

        assertThat(specks).hasSize(50);
        for (double[] speck : specks) {
            assertThat(Math.hypot(speck[0], speck[2])).isLessThanOrEqualTo(12.0);
            assertThat(speck[1]).isBetween(-1.0, 6.0);
        }
    }

    @Test
    @DisplayName("none asked, none made")
    void none() {
        assertThat(ShardHaze.specks(new SplittableRandom(3), 0, 12.0)).isEmpty();
    }

    @Test
    @DisplayName("its colour is a dark red that is nearly purple")
    void colour() {
        int rgb = ShardHaze.COLOUR;
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        assertThat(red).isGreaterThan(blue).isLessThan(140);
        assertThat(blue).isGreaterThan(green * 2);
    }
}
