package de.raindancer.modules.xaeromap;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.modules.xaeromap.model.MapAudience;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every default, spelled out by name — see {@code MannequinSettingsTest} for why a record with eight
 * components is not trusted to have them in the order somebody meant.
 */
class XaeroMapSettingsTest {

    private final XaeroMapSettings defaults = XaeroMapSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("both halves on, everybody, one percent, five seconds, 512 chunks")
        void eachOneByName() {
            assertThat(defaults.worldIds()).isTrue();
            assertThat(defaults.claims()).isTrue();
            assertThat(defaults.shownTo()).isEqualTo(MapAudience.EVERYBODY);
            assertThat(defaults.chunkCoveragePercent()).isEqualTo(1);
            assertThat(defaults.refreshSeconds()).isEqualTo(5);
            assertThat(defaults.chunksPerRefresh()).isEqualTo(512);
            assertThat(defaults.ownColour()).isEqualTo(NamedTextColor.GREEN);
            assertThat(defaults.sharedColour()).isEqualTo(NamedTextColor.AQUA);
        }

        @Test
        @DisplayName("a map per world costs nothing, so it is on out of the box")
        void thecheapHalfIsOn() {
            assertThat(defaults.worldIds())
                    .as("one packet per world change, and without it every world on the server "
                            + "draws over one shared map — there is no reason to make somebody ask")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("clamping")
    class Clamping {

        @Test
        @DisplayName("the refresh interval is clamped into its declared range")
        void therefreshIsClamped() {
            assertThat(defaults.withRefreshSeconds(0).refresh()).isEqualTo(Duration.ofSeconds(2));
            assertThat(defaults.withRefreshSeconds(99_999).refresh()).isEqualTo(Duration.ofSeconds(300));
            assertThat(defaults.withRefreshSeconds(30).refresh()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("the coverage threshold is clamped, and never zero")
        void thethresholdIsClamped() {
            assertThat(defaults.withChunkCoveragePercent(0).coveragePercentClamped()).isEqualTo(1);
            assertThat(defaults.withChunkCoveragePercent(500).coveragePercentClamped()).isEqualTo(100);
            assertThat(defaults.withChunkCoveragePercent(25).coveragePercentClamped()).isEqualTo(25);
        }

        @Test
        @DisplayName("the chunk budget is clamped, and never so small nothing arrives")
        void thebudgetIsClamped() {
            assertThat(defaults.withChunksPerRefresh(1).chunkBudget()).isEqualTo(16);
            assertThat(defaults.withChunksPerRefresh(99_999).chunkBudget()).isEqualTo(8192);
        }

        @Test
        @DisplayName("an audience nobody set is everybody rather than nothing")
        void theaudienceFallsBack() {
            assertThat(defaults.withShownTo(null).audience()).isEqualTo(MapAudience.EVERYBODY);
        }
    }

    @Nested
    @DisplayName("the schema Core reads")
    class Schema {

        @Test
        @DisplayName("the record is a schema Core can actually build")
        void coreCanReadIt() {
            assertThatCode(() -> SettingsSchema.of(XaeroMapSettings.class, XaeroMapSettings.DEFAULTS))
                    .as("a component Core cannot store is a plugin that fails to enable, and this "
                            + "record has an enum and two colours in it")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("changing one setting leaves the others where they were")
        void thewithersDoNotSwapAnything() {
            XaeroMapSettings changed = defaults
                    .withWorldIds(false)
                    .withClaims(false)
                    .withShownTo(MapAudience.MINE_AND_SHARED)
                    .withChunkCoveragePercent(30)
                    .withRefreshSeconds(60)
                    .withChunksPerRefresh(64)
                    .withOwnColour(NamedTextColor.GOLD)
                    .withSharedColour(NamedTextColor.LIGHT_PURPLE);

            assertThat(changed.worldIds()).isFalse();
            assertThat(changed.claims()).isFalse();
            assertThat(changed.shownTo()).isEqualTo(MapAudience.MINE_AND_SHARED);
            assertThat(changed.chunkCoveragePercent()).isEqualTo(30);
            assertThat(changed.refreshSeconds()).isEqualTo(60);
            assertThat(changed.chunksPerRefresh()).isEqualTo(64);
            assertThat(changed.ownColour()).isEqualTo(NamedTextColor.GOLD);
            assertThat(changed.sharedColour())
                    .as("two ints or two colours next to each other in a positional constructor "
                            + "swap silently and compile perfectly")
                    .isEqualTo(NamedTextColor.LIGHT_PURPLE);
        }
    }
}
