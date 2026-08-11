package de.raindancer.modules.chained;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.world.speedrun.conditions.DeathEndCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every default, spelled out by name.
 *
 * <h2>Why this is written out rather than trusted</h2>
 * The constructor is positional and has ten components. Two swapped values compile perfectly and
 * produce a server where the warning cooldown is read as the max distance. Nothing but a test that
 * names each value can tell.
 */
class ChainedSettingsTest {

    private final ChainedSettings defaults = ChainedSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("each one is what it should be, by name")
        void eachOneByName() {
            assertThat(defaults.maxDistanceBlocks()).as("max distance apart").isEqualTo(32);
            assertThat(defaults.warningDistanceBlocks()).as("warn this far before the wall").isEqualTo(5);
            assertThat(defaults.warningCooldownSeconds()).as("wait between refusal messages").isEqualTo(5);
            assertThat(defaults.endCondition()).as("what ends a run")
                    .isEqualTo(ChainedSettings.EndCondition.ADVANCEMENT);
            assertThat(defaults.advancementKey()).as("the advancement to race for")
                    .isEqualTo("minecraft:end/kill_dragon");
            assertThat(defaults.deathPolicy()).as("whose death ends it")
                    .isEqualTo(DeathEndCondition.DeathPolicy.ANY);
            assertThat(defaults.resetOnStart()).as("reset before a run starts").isFalse();
            assertThat(defaults.worldName()).as("which world to reset").isEqualTo("world");
            assertThat(defaults.seedChoice()).as("seed policy")
                    .isEqualTo(ChainedSettings.SeedChoice.RANDOM);
            assertThat(defaults.seedValue()).as("the fixed seed").isZero();
        }

        @Test
        @DisplayName("a new server is safe out of the box")
        void theDefaultsAreTheSafeOnes() {
            // Resetting a world is destructive, so it must not happen unless an owner turns it on.
            assertThat(defaults.resetOnStart()).isFalse();
            assertThat(defaults.maxDistance()).isPositive();
        }
    }

    @Nested
    @DisplayName("reading a value back")
    class Clamping {

        @Test
        @DisplayName("the max distance is clamped, not thrown away")
        void maxDistanceIsClamped() {
            assertThat(defaults.withMaxDistanceBlocks(-5).maxDistance()).isEqualTo(1);
            assertThat(defaults.withMaxDistanceBlocks(50_000).maxDistance()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("the other two are clamped too")
        void theOthersAreClamped() {
            assertThat(defaults.withWarningDistanceBlocks(-1).warningDistance()).isZero();
            assertThat(defaults.withWarningDistanceBlocks(5_000).warningDistance()).isEqualTo(1000);
            assertThat(defaults.withWarningCooldownSeconds(-1).warningCooldown()).isZero();
            assertThat(defaults.withWarningCooldownSeconds(600).warningCooldown()).isEqualTo(60);
        }

        @Test
        @DisplayName("a value inside the range is left exactly alone")
        void nothingElseIsTouched() {
            assertThat(defaults.withMaxDistanceBlocks(100).maxDistance()).isEqualTo(100);
            assertThat(defaults.withWarningCooldownSeconds(10).warningCooldown()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("changing one thing")
    class Withers {

        @Test
        @DisplayName("each wither changes exactly its own component")
        void eachOneChangesOneThing() {
            assertThat(defaults.withMaxDistanceBlocks(9)).isEqualTo(new ChainedSettings(
                    9, 5, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, false, "world", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withWarningDistanceBlocks(9)).isEqualTo(new ChainedSettings(
                    32, 9, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, false, "world", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withWarningCooldownSeconds(9)).isEqualTo(new ChainedSettings(
                    32, 5, 9, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, false, "world", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withEndCondition(ChainedSettings.EndCondition.MANUAL)).isEqualTo(new ChainedSettings(
                    32, 5, 5, ChainedSettings.EndCondition.MANUAL, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, false, "world", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withAdvancementKey("minecraft:story/mine_diamond")).isEqualTo(new ChainedSettings(
                    32, 5, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:story/mine_diamond",
                    DeathEndCondition.DeathPolicy.ANY, false, "world", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withDeathPolicy(DeathEndCondition.DeathPolicy.ALL)).isEqualTo(new ChainedSettings(
                    32, 5, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ALL, false, "world", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withResetOnStart(true)).isEqualTo(new ChainedSettings(
                    32, 5, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, true, "world", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withWorldName("chained_map")).isEqualTo(new ChainedSettings(
                    32, 5, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, false, "chained_map", ChainedSettings.SeedChoice.RANDOM, 0L));
            assertThat(defaults.withSeedChoice(ChainedSettings.SeedChoice.FIXED)).isEqualTo(new ChainedSettings(
                    32, 5, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, false, "world", ChainedSettings.SeedChoice.FIXED, 0L));
            assertThat(defaults.withSeedValue(42L)).isEqualTo(new ChainedSettings(
                    32, 5, 5, ChainedSettings.EndCondition.ADVANCEMENT, "minecraft:end/kill_dragon",
                    DeathEndCondition.DeathPolicy.ANY, false, "world", ChainedSettings.SeedChoice.RANDOM, 42L));
        }

        @Test
        @DisplayName("every component has a wither, so nothing has to be set positionally")
        void thereIsOneForEach() {
            List<String> missing = new ArrayList<>();
            for (RecordComponent component : ChainedSettings.class.getRecordComponents()) {
                String wither = "with" + Character.toUpperCase(component.getName().charAt(0))
                        + component.getName().substring(1);
                boolean found = java.util.Arrays.stream(ChainedSettings.class.getMethods())
                        .anyMatch(method -> method.getName().equals(wither));
                if (!found) {
                    missing.add(component.getName() + " has no " + wither);
                }
            }
            assertThat(missing)
                    .as("the component with no wither is the one somebody will change by writing "
                            + "the whole constructor out, next to nine other values")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the schema the file is written from")
    class Schema {

        @Test
        @DisplayName("every component says which topic it belongs to")
        void everyComponentIsFiled() {
            List<String> unfiled = new ArrayList<>();
            for (RecordComponent component : ChainedSettings.class.getRecordComponents()) {
                if (component.getAnnotation(In.class) == null) {
                    unfiled.add(component.getName());
                }
            }
            assertThat(unfiled)
                    .as("a setting with no topic has nowhere to appear in the /settings screens")
                    .isEmpty();
        }

        @Test
        @DisplayName("every component names its key in the file")
        void everyComponentHasAKey() {
            List<String> keyless = new ArrayList<>();
            for (RecordComponent component : ChainedSettings.class.getRecordComponents()) {
                if (component.getAnnotation(Key.class) == null) {
                    keyless.add(component.getName());
                }
            }
            assertThat(keyless)
                    .as("a key derived from the component name changes when the component is "
                            + "renamed, and an owner's configured value is silently replaced by the "
                            + "default")
                    .isEmpty();
        }

        @Test
        @DisplayName("the keys read as configuration rather than as Java")
        void theKeysAreWrittenForAPerson() {
            List<String> odd = new ArrayList<>();
            for (RecordComponent component : ChainedSettings.class.getRecordComponents()) {
                String key = component.getAnnotation(Key.class).value();
                if (!key.matches("[a-z0-9-]+(\\.[a-z0-9-]+)*")) {
                    odd.add(component.getName() + " is written as " + key);
                }
            }
            assertThat(odd)
                    .as("somebody is going to open this file in a text editor")
                    .isEmpty();
        }

        @Test
        @DisplayName("no two components share a key")
        void theKeysAreDistinct() {
            List<String> keys = new ArrayList<>();
            for (RecordComponent component : ChainedSettings.class.getRecordComponents()) {
                keys.add(component.getAnnotation(Key.class).value());
            }
            assertThat(keys).doesNotHaveDuplicates();
        }
    }
}
