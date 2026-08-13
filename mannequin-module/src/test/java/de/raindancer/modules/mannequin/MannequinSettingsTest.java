package de.raindancer.modules.mannequin;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every default, spelled out by name — see {@code RtpSettingsTest} for why this is not trusted. */
class MannequinSettingsTest {

    private final MannequinSettings defaults = MannequinSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("each one is what it should be, by name")
        void eachOneByName() {
            assertThat(defaults.openCreation()).as("anybody may create").isTrue();
            assertThat(defaults.comboWindowMillis()).as("combo window").isEqualTo(2000);
            assertThat(defaults.blockingEnabled()).as("shield blocking").isTrue();
            assertThat(defaults.shieldRangeBlocks()).as("shield range").isEqualTo(4);
            assertThat(defaults.oneShotThreshold()).as("one-shot threshold").isEqualTo(20);
            assertThat(defaults.redstonePulseTicks()).as("redstone pulse length").isEqualTo(20);
            assertThat(defaults.maxHealth())
                    .as("max health matches a normal player's own 20")
                    .isEqualTo(20.0);
            assertThat(defaults.respawnDelaySeconds()).as("respawn delay").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a mannequin can die and comes back")
    class Life {

        @Test
        @DisplayName("max health is clamped, never below one and never above the ceiling")
        void maxHealthIsClamped() {
            assertThat(defaults.withMaxHealth(0).maxHealthClamped()).isEqualTo(1.0);
            assertThat(defaults.withMaxHealth(999_999).maxHealthClamped()).isEqualTo(2000.0);
            assertThat(defaults.withMaxHealth(45).maxHealthClamped()).isEqualTo(45.0);
        }

        @Test
        @DisplayName("the respawn delay is expressed in the ticks Scheduling takes")
        void respawnDelayIsInTicks() {
            assertThat(defaults.withRespawnDelaySeconds(1).respawnDelayTicks()).isEqualTo(20L);
            assertThat(defaults.withRespawnDelaySeconds(5).respawnDelayTicks()).isEqualTo(100L);
            assertThat(defaults.withRespawnDelaySeconds(-3).respawnDelayTicks()).isZero();
        }
    }

    @Nested
    @DisplayName("reading a value back")
    class Clamping {

        @Test
        @DisplayName("the combo window cannot go negative")
        void comboWindowIsClamped() {
            assertThat(defaults.withComboWindowMillis(-5).comboWindow()).isZero();
            assertThat(defaults.withComboWindowMillis(50_000).comboWindow()).isEqualTo(10000);
        }

        @Test
        @DisplayName("the shield range and threshold are clamped too")
        void othersAreClamped() {
            assertThat(defaults.withShieldRangeBlocks(0).shieldRange()).isEqualTo(1);
            assertThat(defaults.withShieldRangeBlocks(999).shieldRange()).isEqualTo(16);
            assertThat(defaults.withOneShotThreshold(0).oneShotThresholdDamage()).isEqualTo(1);
            assertThat(defaults.withOneShotThreshold(9999).oneShotThresholdDamage()).isEqualTo(1000);
            assertThat(defaults.withRedstonePulseTicks(0).redstonePulseTicksClamped()).isEqualTo(1);
            assertThat(defaults.withRedstonePulseTicks(9999).redstonePulseTicksClamped()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("changing one thing")
    class Withers {

        @Test
        @DisplayName("every component has a wither")
        void thereIsOneForEach() {
            List<String> missing = new ArrayList<>();
            for (RecordComponent component : MannequinSettings.class.getRecordComponents()) {
                String wither = "with" + Character.toUpperCase(component.getName().charAt(0))
                        + component.getName().substring(1);
                boolean found = java.util.Arrays.stream(MannequinSettings.class.getMethods())
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
            assertThat(defaults.withComboWindowMillis(500).comboWindowMillis()).isEqualTo(500);
            assertThat(defaults.withComboWindowMillis(500).shieldRangeBlocks())
                    .isEqualTo(defaults.shieldRangeBlocks());
        }
    }

    @Nested
    @DisplayName("the schema the file is written from")
    class Schema {

        @Test
        @DisplayName("every component says which topic it belongs to")
        void everyComponentIsFiled() {
            List<String> unfiled = new ArrayList<>();
            for (RecordComponent component : MannequinSettings.class.getRecordComponents()) {
                if (component.getAnnotation(In.class) == null) {
                    unfiled.add(component.getName());
                }
            }
            assertThat(unfiled).isEmpty();
        }

        @Test
        @DisplayName("every component names its key, and no two share one")
        void everyComponentHasAUniqueKey() {
            List<String> keys = new ArrayList<>();
            for (RecordComponent component : MannequinSettings.class.getRecordComponents()) {
                Key key = component.getAnnotation(Key.class);
                assertThat(key).as(component.getName() + " has no @Key").isNotNull();
                keys.add(key.value());
            }
            assertThat(keys).doesNotHaveDuplicates();
        }
    }
}
