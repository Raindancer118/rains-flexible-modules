package de.raindancer.modules.names;

import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.SettingsSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings, spelled out one at a time.
 *
 * <p>A record has one positional constructor, and two swapped booleans compile perfectly — so the
 * defaults are asserted by name rather than trusted. And the {@link Key} on each component is what an
 * upgrading server's {@code config.yml} already says: a renamed key is not an error anybody sees, it is
 * a setting that silently reverts to its default while the owner's line sits in the file doing nothing.
 */
class NamesSettingsTest {

    @Test
    @DisplayName("every default is what it should be, by name")
    void theDefaults() {
        NamesSettings defaults = NamesSettings.DEFAULTS;

        assertThat(defaults.maxStops()).isEqualTo(8);
        assertThat(defaults.washInCauldron()).isTrue();
        assertThat(defaults.colourMobNames()).isTrue();
    }

    @Test
    @DisplayName("the keys are the ones an upgrading server's config.yml already has")
    void theKeysAreUnchanged() {
        // Renaming any of these is a silent downgrade: the owner's ceiling, cauldron and mob-name
        // decisions all revert, and the lines they wrote stay in the file meaning nothing.
        assertThat(keyOf("maxStops")).isEqualTo("max-gradient-stops");
        assertThat(keyOf("washInCauldron")).isEqualTo("wash-in-cauldron");
        assertThat(keyOf("colourMobNames")).isEqualTo("colour-mob-names");
    }

    @Test
    @DisplayName("every component has a key of its own, so none of them is keyed by its field name")
    void everyComponentIsKeyed() {
        List<String> unkeyed = new ArrayList<>();
        for (RecordComponent component : NamesSettings.class.getRecordComponents()) {
            if (component.getAnnotation(Key.class) == null) {
                unkeyed.add(component.getName());
            }
        }
        assertThat(unkeyed)
                .as("a component with no @Key is keyed by its Java name, which is not what is in "
                        + "anybody's file")
                .isEmpty();
    }

    @Test
    @DisplayName("Core can build a schema from the record, which is what writes config.yml")
    void theSchemaBuilds() {
        // The schema is the file, its comments, its validation and the /settings screens. A record Core
        // cannot read is a module with no configuration at all, and the failure would otherwise happen
        // on somebody's server rather than here.
        SettingsSchema<NamesSettings> schema = SettingsSchema.of(NamesSettings.class,
                NamesSettings.DEFAULTS);

        assertThat(schema.settings()).hasSize(NamesSettings.class.getRecordComponents().length);
        assertThat(schema.settings()).extracting("key")
                .contains("max-gradient-stops", "wash-in-cauldron", "colour-mob-names");
    }

    @Test
    @DisplayName("the ceiling can never be read as nothing at all")
    void theCeilingIsClamped() {
        // Zero out of a hand-edited file would mean no gradients and no solid colours either, which is
        // the whole feature switched off by a number nobody meant as a switch.
        assertThat(NamesSettings.DEFAULTS.withMaxStops(0).stops()).isEqualTo(1);
        assertThat(NamesSettings.DEFAULTS.withMaxStops(-4).stops()).isEqualTo(1);
        // And a crafting table holds nine items, one of which is the thing being painted.
        assertThat(NamesSettings.DEFAULTS.withMaxStops(40).stops()).isEqualTo(8);
        assertThat(NamesSettings.DEFAULTS.withMaxStops(3).stops()).isEqualTo(3);
    }

    @Test
    @DisplayName("each with-er changes exactly one thing")
    void withersChangeOneThingEach() {
        NamesSettings from = NamesSettings.DEFAULTS;

        assertThat(from.withMaxStops(2))
                .isEqualTo(new NamesSettings(2, from.washInCauldron(), from.colourMobNames()));
        assertThat(from.withWashInCauldron(false))
                .isEqualTo(new NamesSettings(from.maxStops(), false, from.colourMobNames()));
        assertThat(from.withColourMobNames(false))
                .isEqualTo(new NamesSettings(from.maxStops(), from.washInCauldron(), false));
    }

    private static String keyOf(String component) {
        for (RecordComponent found : NamesSettings.class.getRecordComponents()) {
            if (found.getName().equals(component)) {
                Key key = found.getAnnotation(Key.class);
                return key == null ? found.getName() : key.value();
            }
        }
        throw new AssertionError("there is no component called " + component + " any more");
    }
}
