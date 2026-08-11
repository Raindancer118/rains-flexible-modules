package de.raindancer.modules.rtp;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.world.protection.FlagPolicy;
import de.raindancer.core.world.teleport.Scatter;
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
 * The constructor is positional and mixes several {@code int}s in a row. Two swapped ones compile
 * perfectly and produce a server where the warm-up is the minimum radius — nothing but a test that
 * names each value can tell.
 */
class RtpSettingsTest {

    private final RtpSettings defaults = RtpSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("each one is what it should be, by name")
        void eachOneByName() {
            assertThat(defaults.warmupSeconds()).as("stand still for").isEqualTo(3);
            assertThat(defaults.cooldownSeconds()).as("wait between goes").isEqualTo(30);
            assertThat(defaults.hurtCancelsWarmup()).as("being hurt cancels").isTrue();
            assertThat(defaults.minRadius()).as("nearest anybody lands").isEqualTo(100);
            assertThat(defaults.maxRadius()).as("furthest anybody lands").isEqualTo(5000);
            assertThat(defaults.safeArrivalRadius()).as("how far to look for ground").isEqualTo(8);
            assertThat(defaults.centreOnPlayer()).as("centred on the player").isFalse();
            assertThat(defaults.disabledWorlds()).as("worlds switched off").isEmpty();
            assertThat(defaults.heightTolerance()).as("how uneven the landing may be").isEqualTo(1);
            assertThat(defaults.safeArrivalPolicy())
                    .as("who decides whether a landing is checked for safety")
                    .isEqualTo(FlagPolicy.AVAILABLE);
        }

        @Test
        @DisplayName("a new server is safe out of the box")
        void theDefaultsAreSensible() {
            assertThat(defaults.maxRadius()).isGreaterThan(defaults.minRadius());
            assertThat(defaults.minRadius())
                    .as("landing right on top of spawn is not what 'random' is for")
                    .isPositive();
        }

        @Test
        @DisplayName("the search radius is modest, because it is also how much world is loaded")
        void theRadiusIsModest() {
            assertThat(defaults.arrivalRadius()).isLessThanOrEqualTo(16);
        }
    }

    @Nested
    @DisplayName("reading a value back")
    class Clamping {

        @Test
        @DisplayName("a negative warm-up is nothing to wait for, not a countdown that never ends")
        void warmupIsClamped() {
            assertThat(defaults.withWarmupSeconds(-5).warmup()).isZero();
            assertThat(defaults.withWarmupSeconds(9_000).warmup()).isEqualTo(60);
        }

        @Test
        @DisplayName("the others are clamped too")
        void theOthersAreClamped() {
            assertThat(defaults.withCooldownSeconds(-1).cooldown()).isZero();
            assertThat(defaults.withSafeArrivalRadius(0).arrivalRadius()).isEqualTo(1);
            assertThat(defaults.withSafeArrivalRadius(1_000).arrivalRadius()).isEqualTo(32);
        }

        @Test
        @DisplayName("a value inside the range is left exactly alone")
        void nothingElseIsTouched() {
            assertThat(defaults.withWarmupSeconds(7).warmup()).isEqualTo(7);
            assertThat(defaults.withCooldownSeconds(90).cooldown()).isEqualTo(90);
        }
    }

    @Nested
    @DisplayName("the ring somebody lands in")
    class WhereTheyLand {

        @Test
        @DisplayName("becomes the one policy Core's Scatter takes, and only once")
        void becomesAScatter() {
            Scatter scatter = defaults.scatter();

            assertThat(scatter.isOn()).isTrue();
            assertThat(scatter.nearest()).isEqualTo(defaults.minRadius());
            assertThat(scatter.furthest()).isEqualTo(defaults.maxRadius());
        }

        @Test
        @DisplayName("two numbers the wrong way round are still a ring — Scatter's own normalising")
        void swappedIsStillARing() {
            Scatter scatter = defaults.withMinRadius(5000).withMaxRadius(100).scatter();

            assertThat(scatter.nearest()).isEqualTo(100);
            assertThat(scatter.furthest()).isEqualTo(5000);
        }
    }

    @Nested
    @DisplayName("who decides whether a landing is checked for safety")
    class SafetyPolicy {

        @Test
        @DisplayName("by default, each player decides for themselves")
        void defaultsToAvailable() {
            assertThat(defaults.safeArrivalPolicy()).isEqualTo(FlagPolicy.AVAILABLE);
        }

        @Test
        @DisplayName("a wither changes only the policy")
        void witherChangesOnlyThePolicy() {
            RtpSettings forced = defaults.withSafeArrivalPolicy(FlagPolicy.FORCED_OFF);

            assertThat(forced.safeArrivalPolicy()).isEqualTo(FlagPolicy.FORCED_OFF);
            assertThat(forced.minRadius()).isEqualTo(defaults.minRadius());
        }
    }

    @Nested
    @DisplayName("which worlds this runs in")
    class Worlds {

        @Test
        @DisplayName("nothing is disabled by default")
        void nothingDisabledByDefault() {
            assertThat(defaults.isDisabled("world")).isFalse();
            assertThat(defaults.isDisabled("world_nether")).isFalse();
        }

        @Test
        @DisplayName("a listed world is disabled, case-insensitively")
        void aListedWorldIsDisabled() {
            RtpSettings configured = defaults.withDisabledWorlds(List.of("Arena", "lobby"));

            assertThat(configured.isDisabled("arena")).isTrue();
            assertThat(configured.isDisabled("ARENA")).isTrue();
            assertThat(configured.isDisabled("lobby")).isTrue();
            assertThat(configured.isDisabled("world")).isFalse();
        }
    }

    @Nested
    @DisplayName("changing one thing")
    class Withers {

        @Test
        @DisplayName("every component has a wither, so nothing has to be set positionally")
        void thereIsOneForEach() {
            List<String> missing = new ArrayList<>();
            for (RecordComponent component : RtpSettings.class.getRecordComponents()) {
                String wither = "with" + Character.toUpperCase(component.getName().charAt(0))
                        + component.getName().substring(1);
                boolean found = java.util.Arrays.stream(RtpSettings.class.getMethods())
                        .anyMatch(method -> method.getName().equals(wither));
                if (!found) {
                    missing.add(component.getName() + " has no " + wither);
                }
            }
            assertThat(missing)
                    .as("the component with no wither is the one somebody will change by writing "
                            + "the whole constructor out, next to several other ints")
                    .isEmpty();
        }

        @Test
        @DisplayName("a wither changes exactly its own component")
        void eachOneChangesOneThing() {
            assertThat(defaults.withWarmupSeconds(9).warmupSeconds()).isEqualTo(9);
            assertThat(defaults.withWarmupSeconds(9).cooldownSeconds())
                    .isEqualTo(defaults.cooldownSeconds());
            assertThat(defaults.withMinRadius(50).minRadius()).isEqualTo(50);
            assertThat(defaults.withMinRadius(50).maxRadius()).isEqualTo(defaults.maxRadius());
        }
    }

    @Nested
    @DisplayName("the schema the file is written from")
    class Schema {

        @Test
        @DisplayName("every component says which topic it belongs to")
        void everyComponentIsFiled() {
            List<String> unfiled = new ArrayList<>();
            for (RecordComponent component : RtpSettings.class.getRecordComponents()) {
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
            for (RecordComponent component : RtpSettings.class.getRecordComponents()) {
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
            for (RecordComponent component : RtpSettings.class.getRecordComponents()) {
                keys.add(component.getAnnotation(Key.class).value());
            }
            assertThat(keys).doesNotHaveDuplicates();
        }
    }
}
