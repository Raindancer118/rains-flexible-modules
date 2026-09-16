package de.raindancer.modules.worldutils.rules;

import de.raindancer.modules.worldutils.model.Dimension;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LandingRuleTest {

    private final LandingRule rule = new LandingRule();

    private static LandingRule.Target nether() {
        return new LandingRule.Target(Dimension.NETHER, 8, 0, 256, 0, 0, 6.0E7);
    }

    private static LandingRule.Target overworld() {
        return new LandingRule.Target(Dimension.OVERWORLD, 1, -64, 320, 0, 0, 6.0E7);
    }

    @Test
    @DisplayName("overworld to nether divides by eight, and stays under the bedrock roof")
    void intoTheNether() {
        LandingRule.Landing landing = rule.between(Dimension.OVERWORLD, 1, 800, 200, -1600, nether()).orElseThrow();

        assertThat(landing.x()).isEqualTo(100);
        assertThat(landing.z()).isEqualTo(-200);
        assertThat(landing.y()).isEqualTo(LandingRule.NETHER_CEILING);
        assertThat(LandingRule.NETHER_CEILING)
                .as("far enough below the roof that the roof is not the nearest place to stand")
                .isLessThanOrEqualTo(100);
        assertThat(landing.surface()).isFalse();
    }

    @Test
    @DisplayName("nether to overworld multiplies by eight and looks for the surface, not a cave")
    void intoTheOverworld() {
        LandingRule.Landing landing = rule.between(Dimension.NETHER, 8, 10, 40, -3, overworld()).orElseThrow();

        assertThat(landing.x()).isEqualTo(80);
        assertThat(landing.z()).isEqualTo(-24);
        assertThat(landing.surface()).isTrue();
    }

    @Test
    @DisplayName("a scaled position outside the target's border is pulled back inside it")
    void insideTheBorder() {
        LandingRule.Target small = new LandingRule.Target(Dimension.OVERWORLD, 1, -64, 320, 0, 0, 2000);

        LandingRule.Landing landing = rule.between(Dimension.NETHER, 8, 1000, 64, -1000, small).orElseThrow();

        assertThat(landing.x()).isEqualTo(999);
        assertThat(landing.z()).isEqualTo(-999);
    }

    @Test
    @DisplayName("the End, either way, and the same dimension, have no corresponding place")
    void noCorrespondingPlace() {
        LandingRule.Target end = new LandingRule.Target(Dimension.END, 1, 0, 256, 0, 0, 6.0E7);

        assertThat(rule.between(Dimension.OVERWORLD, 1, 0, 64, 0, end)).isEmpty();
        assertThat(rule.between(Dimension.END, 1, 0, 64, 0, overworld())).isEmpty();
        assertThat(rule.between(Dimension.OVERWORLD, 1, 0, 64, 0, overworld())).isEmpty();
    }

    @Test
    @DisplayName("arriving without a corresponding place: their bed when it is in that world, its spawn otherwise")
    void arrivalPoint() {
        World target = mock(World.class);
        World elsewhere = mock(World.class);
        Location spawn = mock(Location.class);
        when(target.getSpawnLocation()).thenReturn(spawn);
        Location bedHere = mock(Location.class);
        when(bedHere.isWorldLoaded()).thenReturn(true);
        when(bedHere.getWorld()).thenReturn(target);
        Location bedElsewhere = mock(Location.class);
        when(bedElsewhere.isWorldLoaded()).thenReturn(true);
        when(bedElsewhere.getWorld()).thenReturn(elsewhere);

        assertThat(LandingRule.arrivalPoint(bedHere, target)).isSameAs(bedHere);
        assertThat(LandingRule.arrivalPoint(bedElsewhere, target)).isSameAs(spawn);
        assertThat(LandingRule.arrivalPoint(null, target)).isSameAs(spawn);
    }
}
