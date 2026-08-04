package de.raindancer.modules.pack;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.SettingsSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this server is configured to wear.
 *
 * <p>Small, and worth writing out: the defaults are a live URL, and a typo in one of them is a module
 * that comes up, logs nothing alarming and sends nobody a pack.
 */
class PackSettingsTest {

    private final PackSettings defaults = PackSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("each one is what it should be, by name")
        void eachOneByName() {
            assertThat(defaults.name()).isEqualTo("yeukpack");
            assertThat(defaults.url())
                    .isEqualTo("https://mc-packs.raindancer118.de/yeukpack/yeukpack.zip");
            assertThat(defaults.sha1())
                    .as("looked up from the host, so updating the pack needs no edit here")
                    .isEmpty();
            assertThat(defaults.lookUpHash()).isTrue();
            assertThat(defaults.required()).isTrue();
            assertThat(defaults.prompt()).isNotBlank();
        }

        @Test
        @DisplayName("it points somewhere, so a server that configures nothing still gets the pack")
        void itWorksOutOfTheBox() {
            assertThat(defaults.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("an empty url means no pack at all, which is a real answer")
        void emptyMeansOff() {
            // The way to switch the module off without uninstalling it. Not a failure state — the
            // service says so at info rather than warning about it.
            assertThat(defaults.withUrl("").isConfigured()).isFalse();
            assertThat(defaults.withUrl("   ").isConfigured()).isFalse();
        }
    }

    @Nested
    @DisplayName("where the hash is published")
    class HashUrl {

        @Test
        @DisplayName("sha1.txt sits beside the pack")
        void besideThePack() {
            // Derived rather than configured: two links in a file are two things to keep in step, and
            // the one nobody updates is the one that goes on serving yesterday's hash.
            assertThat(defaults.hashUrl())
                    .isEqualTo("https://mc-packs.raindancer118.de/yeukpack/sha1.txt");
        }

        @Test
        @DisplayName("the file name is what the hash file lists it under")
        void theFileName() {
            assertThat(defaults.fileName()).isEqualTo("yeukpack.zip");
        }

        @Test
        @DisplayName("nothing configured means nothing to look up, rather than a broken link")
        void nothingConfigured() {
            assertThat(defaults.withUrl("").hashUrl()).isEmpty();
            assertThat(defaults.withUrl("").fileName()).isEmpty();
        }

        @Test
        @DisplayName("a url with no path at all does not produce a nonsense lookup")
        void aStrangeUrl() {
            assertThat(defaults.withUrl("yeukpack.zip").hashUrl()).isEmpty();
            assertThat(defaults.withUrl("yeukpack.zip").fileName()).isEqualTo("yeukpack.zip");
        }
    }

    @Nested
    @DisplayName("the schema the file comes from")
    class Schema {

        @Test
        @DisplayName("every component has a config path and a topic")
        void everythingIsFiledSomewhere() {
            List<String> unfiled = new ArrayList<>();
            for (RecordComponent component : PackSettings.class.getRecordComponents()) {
                if (component.getAnnotation(Key.class) == null) {
                    unfiled.add(component.getName() + " has no @Key");
                }
                if (component.getAnnotation(In.class) == null) {
                    unfiled.add(component.getName() + " has no @In");
                }
            }
            assertThat(unfiled).isEmpty();
        }

        @Test
        @DisplayName("the store knows exactly these keys")
        void theSchemaMatchesTheRecord() {
            assertThat(SettingsSchema.of(PackSettings.class, PackSettings.DEFAULTS).keys())
                    .hasSize(PackSettings.class.getRecordComponents().length)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("there is one wither per component, so none can be unreachable")
        void everyComponentHasOne() {
            List<String> missing = new ArrayList<>();
            for (RecordComponent component : PackSettings.class.getRecordComponents()) {
                String wanted = "with" + Character.toUpperCase(component.getName().charAt(0))
                        + component.getName().substring(1);
                if (java.util.Arrays.stream(PackSettings.class.getMethods())
                        .noneMatch(method -> method.getName().equals(wanted))) {
                    missing.add(wanted);
                }
            }
            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("each with… leaves the others alone")
        void nothingElseMoves() {
            assertThat(defaults.withRequired(false))
                    .isEqualTo(new PackSettings(defaults.name(), defaults.url(), defaults.sha1(),
                            defaults.lookUpHash(), false, defaults.prompt()));
            assertThat(defaults.withLookUpHash(false))
                    .isEqualTo(new PackSettings(defaults.name(), defaults.url(), defaults.sha1(),
                            false, defaults.required(), defaults.prompt()));
        }
    }
}
