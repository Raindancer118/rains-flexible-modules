package de.raindancer.modules.warp;

import de.raindancer.core.data.settings.In;
import de.raindancer.core.world.teleport.Companions;
import de.raindancer.core.data.settings.Key;
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
 * Because the constructor is positional and has twelve components, seven of which are {@code int}. Two
 * swapped {@code int}s compile perfectly and produce a server where the warm-up is the search radius:
 * eight seconds of standing still, and a warp that looks three blocks for solid ground. Nothing but a
 * test that names each value can tell.
 *
 * <p>Not hypothetical. Four components were added to the middle of this record and the defaults were
 * rewritten in the wrong order in the same edit — this test is what said so.
 */
class WarpSettingsTest {

    private final WarpSettings defaults = WarpSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("each one is what it should be, by name")
        void eachOneByName() {
            assertThat(defaults.warmupSeconds()).as("stand still for").isEqualTo(3);
            assertThat(defaults.cooldownSeconds()).as("wait between warps").isEqualTo(15);
            assertThat(defaults.hurtCancelsWarmup()).as("being hurt cancels").isTrue();
            assertThat(defaults.safeArrival()).as("look for somewhere safe").isTrue();
            assertThat(defaults.safeArrivalRadius()).as("how far to look").isEqualTo(8);
            assertThat(defaults.mostWarps()).as("warps one server may have").isEqualTo(200);
            assertThat(defaults.longestName()).as("longest name").isEqualTo(24);
            assertThat(defaults.bringWhatYouLead()).as("bring what you lead").isTrue();
            assertThat(defaults.bringNearbyPets()).as("bring animals nearby").isFalse();
            assertThat(defaults.bringRadius()).as("how far animals may be").isEqualTo(8);
            assertThat(defaults.bringAtMost()).as("most that may come").isEqualTo(10);
            assertThat(defaults.useCategories()).as("group into categories").isTrue();
        }

        @Test
        @DisplayName("a new server is safe out of the box")
        void theDefaultsAreTheSafeOnes() {
            // The two that matter on a server nobody has configured. Arriving unchecked drops
            // somebody into whatever is at the coordinates — which for a warp set on a boat, or one
            // whose ground has since been mined out, is a fall.
            assertThat(defaults.safeArrival()).isTrue();
            assertThat(defaults.warmup())
                    .as("a warm-up of zero means running out of a fight through a warp is free")
                    .isPositive();
        }

        @Test
        @DisplayName("the search radius is modest, because it is also how much world is loaded")
        void theRadiusIsModest() {
            // A large radius is a pause for everybody on the server, not only the person warping.
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
        @DisplayName("the other three are clamped too")
        void theOthersAreClamped() {
            assertThat(defaults.withCooldownSeconds(-1).cooldown()).isZero();
            assertThat(defaults.withSafeArrivalRadius(0).arrivalRadius()).isEqualTo(1);
            assertThat(defaults.withSafeArrivalRadius(1_000).arrivalRadius()).isEqualTo(32);
            assertThat(defaults.withLongestName(1).nameLimit()).isEqualTo(3);
            assertThat(defaults.withMostWarps(0).warpLimit()).isEqualTo(1);
        }

        @Test
        @DisplayName("a value inside the range is left exactly alone")
        void nothingElseIsTouched() {
            // A clamp that also rounded, or that returned a default for anything it disliked, would
            // be a config where the number in the file is not the number in force.
            assertThat(defaults.withWarmupSeconds(7).warmup()).isEqualTo(7);
            assertThat(defaults.withCooldownSeconds(90).cooldown()).isEqualTo(90);
        }
    }

    @Nested
    @DisplayName("what travels with the player")
    class Companionship {

        @Test
        @DisplayName("the two switches become the one policy Core takes")
        void theSwitchesBecomeAPolicy() {
            // Two booleans in a file and a three-way policy in code are the same decision written
            // twice. This is the one place they are joined up, so there is nowhere else for them to
            // disagree.
            assertThat(defaults.withBringWhatYouLead(false).companions().bringsAnything())
                    .as("switched off, nothing travels — and Core then does not even look around")
                    .isFalse();

            Companions led = defaults.withBringWhatYouLead(true).withBringNearbyPets(false)
                    .companions();
            assertThat(led.bringsAnything()).isTrue();
            assertThat(led.bringsNearbyPets()).isFalse();

            Companions andPets = defaults.withBringWhatYouLead(true).withBringNearbyPets(true)
                    .companions();
            assertThat(andPets.bringsNearbyPets()).isTrue();
        }

        @Test
        @DisplayName("animals nearby cannot be brought without leads being brought")
        void petsNeedLeadsOn() {
            // The pair only reads one way round. "Bring the dog following me but not the dog I put
            // on a lead" is not a thing anybody means, and offering it as a state would be a menu
            // with a nonsense square in it.
            assertThat(defaults.withBringWhatYouLead(false).withBringNearbyPets(true)
                    .companions().bringsAnything())
                    .isFalse();
        }

        @Test
        @DisplayName("the radius and the ceiling are carried across, and clamped by Core")
        void theNumbersAreCarried() {
            Companions policy = defaults.withBringNearbyPets(true)
                    .withBringRadius(1_000).withBringAtMost(999).companions();

            assertThat(policy.radius()).isEqualTo(Companions.FURTHEST);
            assertThat(policy.most()).isEqualTo(Companions.MOST_ALLOWED);
        }
    }

    @Nested
    @DisplayName("changing one thing")
    class Withers {

        @Test
        @DisplayName("each wither changes exactly its own component")
        void eachOneChangesOneThing() {
            // The whole reason the withers exist. A call site that spelled out the positional
            // constructor to change one number is a call site where two of the four ints can be
            // swapped without the compiler noticing.
            assertThat(defaults.withWarmupSeconds(9))
                    .isEqualTo(new WarpSettings(9, 15, true, true, 8, 200, 24, true, false, 8, 10, true));
            assertThat(defaults.withCooldownSeconds(60))
                    .isEqualTo(new WarpSettings(3, 60, true, true, 8, 200, 24, true, false, 8, 10, true));
            assertThat(defaults.withHurtCancelsWarmup(false))
                    .isEqualTo(new WarpSettings(3, 15, false, true, 8, 200, 24, true, false, 8, 10, true));
            assertThat(defaults.withSafeArrival(false))
                    .isEqualTo(new WarpSettings(3, 15, true, false, 8, 200, 24, true, false, 8, 10, true));
            assertThat(defaults.withSafeArrivalRadius(12))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 12, 200, 24, true, false, 8, 10, true));
            assertThat(defaults.withMostWarps(50))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 8, 50, 24, true, false, 8, 10, true));
            assertThat(defaults.withLongestName(32))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 8, 200, 32, true, false, 8, 10, true));
            assertThat(defaults.withBringWhatYouLead(false))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 8, 200, 24, false, false, 8, 10, true));
            assertThat(defaults.withBringNearbyPets(true))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 8, 200, 24, true, true, 8, 10, true));
            assertThat(defaults.withBringRadius(16))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 8, 200, 24, true, false, 16, 10, true));
            assertThat(defaults.withBringAtMost(4))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 8, 200, 24, true, false, 8, 4, true));
            assertThat(defaults.withUseCategories(false))
                    .isEqualTo(new WarpSettings(3, 15, true, true, 8, 200, 24, true, false, 8, 10, false));
        }

        @Test
        @DisplayName("every component has a wither, so nothing has to be set positionally")
        void thereIsOneForEach() {
            List<String> missing = new ArrayList<>();
            for (RecordComponent component : WarpSettings.class.getRecordComponents()) {
                String wither = "with" + Character.toUpperCase(component.getName().charAt(0))
                        + component.getName().substring(1);
                boolean found = java.util.Arrays.stream(WarpSettings.class.getMethods())
                        .anyMatch(method -> method.getName().equals(wither));
                if (!found) {
                    missing.add(component.getName() + " has no " + wither);
                }
            }
            assertThat(missing)
                    .as("the component with no wither is the one somebody will change by writing "
                            + "the whole constructor out, next to three other ints")
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
            for (RecordComponent component : WarpSettings.class.getRecordComponents()) {
                if (component.getAnnotation(In.class) == null) {
                    unfiled.add(component.getName());
                }
            }
            assertThat(unfiled)
                    .as("a setting with no topic has nowhere to appear in the /settings screens, so "
                            + "it exists in the file and nowhere a person would look")
                    .isEmpty();
        }

        @Test
        @DisplayName("every component names its key in the file")
        void everyComponentHasAKey() {
            List<String> keyless = new ArrayList<>();
            for (RecordComponent component : WarpSettings.class.getRecordComponents()) {
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
            for (RecordComponent component : WarpSettings.class.getRecordComponents()) {
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
            // Two components on one key is one of them silently winning, and which one depends on
            // the order the schema happened to walk them in.
            List<String> keys = new ArrayList<>();
            for (RecordComponent component : WarpSettings.class.getRecordComponents()) {
                keys.add(component.getAnnotation(Key.class).value());
            }
            assertThat(keys).doesNotHaveDuplicates();
        }
    }
}
