package de.raindancer.modules.farmworld;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.world.teleport.Companions;
import de.raindancer.modules.farmworld.model.Scatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every default, spelled out by name.
 *
 * <h2>Why this is written out rather than trusted</h2>
 * Because the constructor is positional and has twelve components, seven of which are {@code int}. Two swapped
 * {@code int}s compile perfectly and produce a server where the warm-up is the search radius, or where everybody
 * lands between four thousand and two hundred and fifty blocks out instead of the other way round. Nothing but a
 * test that names each value can tell.
 */
class FarmWorldSettingsTest {

    private final FarmWorldSettings defaults = FarmWorldSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("each one is what it should be, by name")
        void eachOneByName() {
            assertThat(defaults.warmupSeconds()).as("stand still for").isEqualTo(5);
            assertThat(defaults.cooldownSeconds()).as("wait between trips").isEqualTo(60);
            assertThat(defaults.hurtCancelsWarmup()).as("being hurt cancels").isTrue();
            assertThat(defaults.safeArrivalRadius()).as("how far to look for ground").isEqualTo(8);
            assertThat(defaults.bringWhatYouLead()).as("bring what you lead").isTrue();
            assertThat(defaults.bringNearbyPets()).as("bring animals nearby").isFalse();
            assertThat(defaults.bringRadius()).as("how far animals may be").isEqualTo(8);
            assertThat(defaults.bringAtMost()).as("most that may come").isEqualTo(10);
            assertThat(defaults.scatterArrivals()).as("scatter arrivals").isTrue();
            assertThat(defaults.scatterNearest()).as("nearest to the middle").isEqualTo(250);
            assertThat(defaults.scatterFurthest()).as("furthest from the middle").isEqualTo(4000);
            assertThat(defaults.warnMinutes()).as("warn this long before").isEqualTo(15);
        }

        @Test
        @DisplayName("a new server gets a farm world that works out of the box")
        void theDefaultsAreTheOnesThatWork()  {
            // The two that decide whether the feature is worth having at all. Unscattered, the ground around one
            // spot is bare within a day and every arrival after that is a walk. Unwarned, the first regeneration
            // is reported as a bug.
            assertThat(defaults.scatter().isOn())
                    .as("this is what makes a farm world one rather than a second overworld")
                    .isTrue();
            assertThat(defaults.warnLead())
                    .as("a server nobody configured still warns before three worlds are deleted")
                    .isPositive();
        }

        @Test
        @DisplayName("the wait between trips is long enough to make where you landed matter")
        void thereIsAWait() {
            // Without one, arriving somewhere unpromising and going straight back for another roll of the dice is
            // free — and then the scatter decides nothing at all.
            assertThat(defaults.cooldown()).isGreaterThanOrEqualTo(30);
        }

        @Test
        @DisplayName("the search radius is modest, because it is also how much world is generated")
        void theRadiusIsModest() {
            assertThat(defaults.arrivalRadius()).isLessThanOrEqualTo(16);
        }

        @Test
        @DisplayName("the scatter reaches far enough to be worth doing and not far enough to fill a disk")
        void theRingIsSensible() {
            // Four thousand blocks is about fifty million square metres of ring: enough that two arrivals
            // colliding is a coincidence, and small enough that a server does not generate a terabyte.
            assertThat(defaults.scatterFurthest()).isBetween(1000, 20_000);
            assertThat(defaults.scatterNearest()).isPositive();
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
            assertThat(defaults.withCooldownSeconds(999_999).cooldown()).isEqualTo(86_400);
            assertThat(defaults.withSafeArrivalRadius(0).arrivalRadius()).isEqualTo(1);
            assertThat(defaults.withSafeArrivalRadius(1_000).arrivalRadius()).isEqualTo(32);
            assertThat(defaults.withWarnMinutes(-5).warnLead()).isEqualTo(Duration.ZERO);
            assertThat(defaults.withWarnMinutes(99_999).warnLead())
                    .isEqualTo(Duration.ofMinutes(1440));
        }

        @Test
        @DisplayName("a value inside the range is left exactly alone")
        void nothingElseIsTouched() {
            // A clamp that also rounded, or that returned a default for anything it disliked, would be a config
            // where the number in the file is not the number in force.
            assertThat(defaults.withWarmupSeconds(7).warmup()).isEqualTo(7);
            assertThat(defaults.withCooldownSeconds(90).cooldown()).isEqualTo(90);
            assertThat(defaults.withWarnMinutes(30).warnLead()).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("the wait is read back as the duration Core's cooldown takes")
        void theWaitBecomesADuration() {
            assertThat(defaults.cooldownFor()).isEqualTo(Duration.ofSeconds(60));
            assertThat(defaults.withCooldownSeconds(0).cooldownFor())
                    .as("zero is how the wait is switched off, and Cooldowns reads it that way")
                    .isEqualTo(Duration.ZERO);
        }
    }

    @Nested
    @DisplayName("where somebody lands")
    class Arrivals {

        @Test
        @DisplayName("the three values become the one policy")
        void theSettingsBecomeAPolicy() {
            // Three values in a file and one policy in code are the same decision written twice. This is the one
            // place they are joined up, so there is nowhere else for them to disagree.
            Scatter scatter = defaults.scatter();

            assertThat(scatter.nearest()).isEqualTo(defaults.scatterNearest());
            assertThat(scatter.furthest()).isEqualTo(defaults.scatterFurthest());
            assertThat(defaults.withScatterArrivals(false).scatter().isOn()).isFalse();
        }
    }

    @Nested
    @DisplayName("what travels with the player")
    class Companionship {

        @Test
        @DisplayName("the two switches become the one policy Core takes")
        void theSwitchesBecomeAPolicy() {
            assertThat(defaults.withBringWhatYouLead(false).companions().bringsAnything())
                    .as("switched off, nothing travels — and Core then does not even look around")
                    .isFalse();

            Companions led = defaults.withBringWhatYouLead(true).withBringNearbyPets(false)
                    .companions();
            assertThat(led.bringsAnything()).isTrue();
            assertThat(led.bringsNearbyPets()).isFalse();

            Companions andPets = defaults.withBringNearbyPets(true).companions();
            assertThat(andPets.bringsNearbyPets()).isTrue();
        }

        @Test
        @DisplayName("an absurd radius is the largest sensible search, not an exception at the first trip")
        void theRadiusIsClampedByCore() {
            assertThat(defaults.withBringNearbyPets(true).withBringRadius(1_000)
                    .companions().radius())
                    .isEqualTo(Companions.FURTHEST);
        }
    }

    @Nested
    @DisplayName("every component changes exactly one thing")
    class Withers {

        @Test
        @DisplayName("each with… leaves the other eleven alone")
        void nothingElseMoves() {
            // What a positional constructor with twelve components gets wrong: a wither written by copying the
            // one above it and changing the wrong position. Every one of these would compile.
            assertThat(defaults.withWarmupSeconds(1))
                    .isEqualTo(new FarmWorldSettings(1, 60, true, 8, true, false, 8, 10, true, 250,
                            4000, 15));
            assertThat(defaults.withCooldownSeconds(1))
                    .isEqualTo(new FarmWorldSettings(5, 1, true, 8, true, false, 8, 10, true, 250,
                            4000, 15));
            assertThat(defaults.withHurtCancelsWarmup(false))
                    .isEqualTo(new FarmWorldSettings(5, 60, false, 8, true, false, 8, 10, true, 250,
                            4000, 15));
            assertThat(defaults.withSafeArrivalRadius(1))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 1, true, false, 8, 10, true, 250,
                            4000, 15));
            assertThat(defaults.withBringWhatYouLead(false))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, false, false, 8, 10, true, 250,
                            4000, 15));
            assertThat(defaults.withBringNearbyPets(true))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, true, true, 8, 10, true, 250,
                            4000, 15));
            assertThat(defaults.withBringRadius(1))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, true, false, 1, 10, true, 250,
                            4000, 15));
            assertThat(defaults.withBringAtMost(1))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, true, false, 8, 1, true, 250,
                            4000, 15));
            assertThat(defaults.withScatterArrivals(false))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, true, false, 8, 10, false, 250,
                            4000, 15));
            assertThat(defaults.withScatterNearest(1))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, true, false, 8, 10, true, 1,
                            4000, 15));
            assertThat(defaults.withScatterFurthest(1))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, true, false, 8, 10, true, 250,
                            1, 15));
            assertThat(defaults.withWarnMinutes(1))
                    .isEqualTo(new FarmWorldSettings(5, 60, true, 8, true, false, 8, 10, true, 250,
                            4000, 1));
        }

        @Test
        @DisplayName("there is one wither per component, so none can be unreachable")
        void everyComponentHasOne() {
            List<String> missing = new ArrayList<>();
            for (RecordComponent component : FarmWorldSettings.class.getRecordComponents()) {
                String wanted = "with" + Character.toUpperCase(component.getName().charAt(0))
                        + component.getName().substring(1);
                boolean found = java.util.Arrays.stream(FarmWorldSettings.class.getMethods())
                        .anyMatch(method -> method.getName().equals(wanted));
                if (!found) {
                    missing.add(wanted);
                }
            }
            assertThat(missing)
                    .as("a component with no wither is one only the positional constructor can set")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the schema the file comes from")
    class Schema {

        @Test
        @DisplayName("every component has a config path and a topic")
        void everythingIsFiledSomewhere() {
            List<String> unfiled = new ArrayList<>();
            for (RecordComponent component : FarmWorldSettings.class.getRecordComponents()) {
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
        @DisplayName("the store knows exactly the twelve keys")
        void theSchemaMatchesTheRecord() {
            assertThat(SettingsSchema.of(FarmWorldSettings.class, FarmWorldSettings.DEFAULTS).keys())
                    .hasSize(FarmWorldSettings.class.getRecordComponents().length)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a key is a config path and never a component name")
        void keysAreDashedNotCamelCase() {
            // bringRadius and bring-radius both look right at a glance, and only one of them is what the store
            // answers to.
            for (RecordComponent component : FarmWorldSettings.class.getRecordComponents()) {
                assertThat(component.getAnnotation(Key.class).value())
                        .as("%s", component.getName())
                        .matches("[a-z0-9-]+");
            }
        }
    }
}
