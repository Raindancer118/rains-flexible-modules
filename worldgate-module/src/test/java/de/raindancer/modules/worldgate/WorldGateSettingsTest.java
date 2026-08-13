package de.raindancer.modules.worldgate;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every default, spelled out by name — the three world names an owner can tell this module about.
 */
class WorldGateSettingsTest {

    private final WorldGateSettings defaults = WorldGateSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("each one is what it should be, by name")
        void eachOneByName() {
            assertThat(defaults.netherWorld()).isEqualTo("world_nether");
            assertThat(defaults.endWorld()).isEqualTo("world_the_end");
            assertThat(defaults.overworldWorld()).isEqualTo("world");
        }
    }

    @Nested
    @DisplayName("reading a value back")
    class BlankValues {

        @Test
        @DisplayName("a blank world name falls back to the default rather than naming no world at all")
        void blankFallsBackToDefault() {
            assertThat(new WorldGateSettings("", "", "").netherWorld()).isEqualTo("world_nether");
            assertThat(new WorldGateSettings(null, null, null).endWorld()).isEqualTo("world_the_end");
            assertThat(new WorldGateSettings("  ", "  ", "  ").overworldWorld()).isEqualTo("world");
        }

        @Test
        @DisplayName("a real value is left exactly alone")
        void aRealValueIsUntouched() {
            WorldGateSettings custom = new WorldGateSettings("nether2", "end2", "overworld2");

            assertThat(custom.netherWorld()).isEqualTo("nether2");
            assertThat(custom.endWorld()).isEqualTo("end2");
            assertThat(custom.overworldWorld()).isEqualTo("overworld2");
        }
    }

    @Nested
    @DisplayName("changing one thing")
    class Withers {

        @Test
        @DisplayName("every component has a wither, so nothing has to be set positionally")
        void thereIsOneForEach() {
            List<String> missing = new ArrayList<>();
            for (RecordComponent component : WorldGateSettings.class.getRecordComponents()) {
                String wither = "with" + Character.toUpperCase(component.getName().charAt(0))
                        + component.getName().substring(1);
                boolean found = Arrays.stream(WorldGateSettings.class.getMethods())
                        .anyMatch(method -> method.getName().equals(wither));
                if (!found) {
                    missing.add(component.getName() + " has no " + wither);
                }
            }
            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("a wither changes exactly its own component")
        void eachOneChangesOneThing() {
            assertThat(defaults.withNetherWorld("nether2").netherWorld()).isEqualTo("nether2");
            assertThat(defaults.withNetherWorld("nether2").endWorld()).isEqualTo(defaults.endWorld());
            assertThat(defaults.withEndWorld("end2").endWorld()).isEqualTo("end2");
            assertThat(defaults.withOverworldWorld("ow2").overworldWorld()).isEqualTo("ow2");
        }
    }

    @Nested
    @DisplayName("the schema the file is written from")
    class Schema {

        @Test
        @DisplayName("every component says which topic it belongs to")
        void everyComponentIsFiled() {
            List<String> unfiled = new ArrayList<>();
            for (RecordComponent component : WorldGateSettings.class.getRecordComponents()) {
                if (component.getAnnotation(In.class) == null) {
                    unfiled.add(component.getName());
                }
            }
            assertThat(unfiled).isEmpty();
        }

        @Test
        @DisplayName("every component names its key in the file")
        void everyComponentHasAKey() {
            List<String> keyless = new ArrayList<>();
            for (RecordComponent component : WorldGateSettings.class.getRecordComponents()) {
                if (component.getAnnotation(Key.class) == null) {
                    keyless.add(component.getName());
                }
            }
            assertThat(keyless).isEmpty();
        }

        @Test
        @DisplayName("no two components share a key")
        void theKeysAreDistinct() {
            List<String> keys = new ArrayList<>();
            for (RecordComponent component : WorldGateSettings.class.getRecordComponents()) {
                keys.add(component.getAnnotation(Key.class).value());
            }
            assertThat(keys).doesNotHaveDuplicates();
        }
    }
}
