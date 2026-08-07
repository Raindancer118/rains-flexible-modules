package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five sponsor combat items, tested entirely through {@link CombatItemService}'s {@code attempts}
 * methods — the seam interfaces below are fakes that record what they were asked to do, so none of this
 * needs a running server. See {@link CombatItemService}'s class note for why the storm and the aura are not
 * scheduled here at all: the seam is asked once, with the numbers the item runs with, and what happens after
 * that (staggering bolts, pulsing every so often) is the seam implementation's own problem.
 */
@DisplayName("CombatItemService")
class CombatItemServiceTest {

    /** Records the last call, or {@code null} if it was never asked. */
    private static final class FakeSmokescreen implements CombatItemService.Smokescreen {
        Object[] lastCall;
        boolean answer = true;

        @Override
        public boolean detonate(ItemUse use, double radius, Duration enemyEffectDuration,
                                 Duration invisibilityDuration) {
            lastCall = new Object[] {use, radius, enemyEffectDuration, invisibilityDuration};
            return answer;
        }
    }

    private static final class FakeMedicine implements CombatItemService.Medicine {
        Object[] lastCall;
        boolean answer = true;

        @Override
        public boolean treat(ItemUse use, Duration windUp, Duration regenerationDuration,
                              int regenerationAmplifier, Duration absorptionDuration,
                              int absorptionAmplifier) {
            lastCall = new Object[] {use, windUp, regenerationDuration, regenerationAmplifier,
                    absorptionDuration, absorptionAmplifier};
            return answer;
        }
    }

    private static final class FakeStorm implements CombatItemService.Storm {
        Object[] lastCall;
        boolean answer = true;

        @Override
        public boolean callDown(ItemUse use, int bolts, Duration boltDelay, int damageRadius, double bonusDamage,
                                 Duration fireDuration, boolean knockUp) {
            lastCall = new Object[] {use, bolts, boltDelay, damageRadius, bonusDamage, fireDuration, knockUp};
            return answer;
        }
    }

    private static final class FakeSplash implements CombatItemService.Splash {
        Object[] lastCall;
        boolean answer = true;

        @Override
        public boolean drench(ItemUse use, double radius, Duration nauseaDuration, Duration blindnessDuration) {
            lastCall = new Object[] {use, radius, nauseaDuration, blindnessDuration};
            return answer;
        }
    }

    private static final class FakeAura implements CombatItemService.Aura {
        Object[] lastCall;
        boolean answer = true;

        @Override
        public boolean protect(ItemUse use, Duration duration, double radius, double damage,
                                Duration pulseInterval, double knockback) {
            lastCall = new Object[] {use, duration, radius, damage, pulseInterval, knockback};
            return answer;
        }
    }

    private FakeSmokescreen smokescreen;
    private FakeMedicine medicine;
    private FakeStorm storm;
    private FakeSplash splash;
    private FakeAura aura;
    private CombatItemService service;

    @BeforeEach
    void setUp() {
        smokescreen = new FakeSmokescreen();
        medicine = new FakeMedicine();
        storm = new FakeStorm();
        splash = new FakeSplash();
        aura = new FakeAura();
        service = new CombatItemService(null, null, smokescreen, medicine, storm, splash, aura,
                HungerGamesSettings.DEFAULTS);
    }

    private static ItemUse use(String ability) {
        return new ItemUse(UUID.randomUUID(), ability, ItemTrigger.RIGHT_CLICK, 0);
    }

    @Nested
    @DisplayName("with no round on at all")
    class OutsideARound {

        // The regression this guards: an earlier version refused every one of these outside
        // GamePhase.RUNNING, so testing an item meant starting a whole tournament first.

        @Test
        @DisplayName("the smoke bomb still works")
        void smokeBombStillWorks() {
            boolean happened = service.throwSmokeBomb(use(CombatItemService.SMOKE_BOMB));

            assertThat(happened).isTrue();
            assertThat(smokescreen.lastCall).isNotNull();
        }

        @Test
        @DisplayName("the medikit still works")
        void medikitStillWorks() {
            boolean happened = service.useMedikit(use(CombatItemService.MEDIKIT));

            assertThat(happened).isTrue();
            assertThat(medicine.lastCall).isNotNull();
        }

        @Test
        @DisplayName("the lightning strike still works")
        void lightningStillWorks() {
            boolean happened = service.callLightning(use(CombatItemService.LIGHTNING_STRIKE));

            assertThat(happened).isTrue();
            assertThat(storm.lastCall).isNotNull();
        }

        @Test
        @DisplayName("krückauwasser can still be thrown")
        void krueckauwasserStillWorks() {
            boolean happened = service.throwKrueckauwasser(use(CombatItemService.KRUECKAUWASSER));

            assertThat(happened).isTrue();
            assertThat(splash.lastCall).isNotNull();
        }

        @Test
        @DisplayName("the aura of protection still goes up")
        void auraStillWorks() {
            boolean happened = service.activateAura(use(CombatItemService.AURA_OF_PROTECTION));

            assertThat(happened).isTrue();
            assertThat(aura.lastCall).isNotNull();
        }
    }

    @Nested
    @DisplayName("a refused seam spends no charge")
    class RefusedSeam {

        @Test
        @DisplayName("a smoke bomb that finds nobody around reports failure, not success")
        void smokeBombDeclined() {
            smokescreen.answer = false;

            boolean happened = service.throwSmokeBomb(use(CombatItemService.SMOKE_BOMB));

            assertThat(happened).isFalse();
            assertThat(smokescreen.lastCall).as("the seam was still asked; it simply declined").isNotNull();
        }

        @Test
        @DisplayName("a medikit that cannot find its holder reports failure")
        void medikitDeclined() {
            medicine.answer = false;

            boolean happened = service.useMedikit(use(CombatItemService.MEDIKIT));

            assertThat(happened).isFalse();
        }

        @Test
        @DisplayName("a lightning strike with nothing to aim at reports failure")
        void lightningDeclined() {
            storm.answer = false;

            boolean happened = service.callLightning(use(CombatItemService.LIGHTNING_STRIKE));

            assertThat(happened).isFalse();
        }

        @Test
        @DisplayName("krückauwasser that lands nowhere useful reports failure")
        void krueckauwasserDeclined() {
            splash.answer = false;

            boolean happened = service.throwKrueckauwasser(use(CombatItemService.KRUECKAUWASSER));

            assertThat(happened).isFalse();
        }

        @Test
        @DisplayName("an aura that fails to activate reports failure")
        void auraDeclined() {
            aura.answer = false;

            boolean happened = service.activateAura(use(CombatItemService.AURA_OF_PROTECTION));

            assertThat(happened).isFalse();
        }
    }

    @Nested
    @DisplayName("the tuned numbers reach the seam, read from the current settings snapshot")
    class NumbersPassThrough {

        private final HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

        @Test
        @DisplayName("the smoke bomb passes its radius and both durations")
        void smokeBombNumbers() {
            ItemUse itemUse = use(CombatItemService.SMOKE_BOMB);

            boolean happened = service.throwSmokeBomb(itemUse);

            assertThat(happened).isTrue();
            // The radius is cast, and the cast is the point: the setting is an int at the old plugin's own
            // key path, and the seam takes a double because a radius is compared against squared distances.
            // Recorded as a Double and asserted as an Integer, containsExactly fails on a value that is
            // numerically identical — which is a nastier way to be wrong than a genuinely different number.
            assertThat(smokescreen.lastCall).containsExactly(itemUse,
                    (double) settings.smokeBombRadius(),
                    Duration.ofSeconds(settings.smokeBombEnemyDuration()),
                    Duration.ofSeconds(settings.smokeBombInvisSeconds()));
        }

        @Test
        @DisplayName("the medikit passes its wind-up, both effect durations and both amplifiers")
        void medikitNumbers() {
            ItemUse itemUse = use(CombatItemService.MEDIKIT);

            boolean happened = service.useMedikit(itemUse);

            assertThat(happened).isTrue();
            assertThat(medicine.lastCall).containsExactly(itemUse,
                    Duration.ofSeconds(settings.medikitCountdownSeconds()),
                    Duration.ofSeconds(settings.medikitRegenSeconds()), settings.medikitRegenLevel() - 1,
                    Duration.ofSeconds(settings.medikitAbsorptionSeconds()),
                    settings.medikitAbsorptionLevel() - 1);
        }

        @Test
        @DisplayName("the wind-up is the one from the settings, so a server that tuned it gets what it set")
        void theWindUpIsTheServersOwn() {
            // The regression this whole seam exists for: the port healed the instant the item was clicked
            // and this number went nowhere at all, which turned the most expensive item in the shop from a
            // gamble taken mid-fight into a free full heal.
            service.settings(de.raindancer.modules.hungergames.Tweak.of(settings,
                    "medikitCountdownSeconds", 7));

            service.useMedikit(use(CombatItemService.MEDIKIT));

            assertThat(medicine.lastCall[1]).isEqualTo(Duration.ofSeconds(7));
        }

        @Test
        @DisplayName("the lightning strike passes the bolt count, delay, radius, damage, fire and knock-up")
        void lightningNumbers() {
            ItemUse itemUse = use(CombatItemService.LIGHTNING_STRIKE);

            boolean happened = service.callLightning(itemUse);

            assertThat(happened).isTrue();
            assertThat(storm.lastCall).containsExactly(itemUse, settings.lightningBoltCount(),
                    Duration.ofMillis(settings.lightningBoltDelay() * 50L), settings.lightningDamageRadius(),
                    (double) settings.lightningBonusDamage(),
                    Duration.ofSeconds(settings.lightningFireTicks() / 20L), settings.lightningKnockup());
        }

        @Test
        @DisplayName("krückauwasser passes its radius, nausea duration and blindness duration")
        void krueckauwasserNumbers() {
            ItemUse itemUse = use(CombatItemService.KRUECKAUWASSER);

            boolean happened = service.throwKrueckauwasser(itemUse);

            assertThat(happened).isTrue();
            assertThat(splash.lastCall).containsExactly(itemUse, (double) settings.krueckauRadius(),
                    Duration.ofSeconds(settings.krueckauNauseaSeconds()),
                    Duration.ofSeconds(settings.krueckauBlindnessSeconds()));
        }

        @Test
        @DisplayName("the aura passes its duration, radius, damage, pulse interval and knockback")
        void auraNumbers() {
            ItemUse itemUse = use(CombatItemService.AURA_OF_PROTECTION);

            boolean happened = service.activateAura(itemUse);

            assertThat(happened).isTrue();
            assertThat(aura.lastCall).containsExactly(itemUse, Duration.ofSeconds(settings.auraDurationSeconds()),
                    (double) settings.auraRadius(), (double) settings.auraDamage(),
                    Duration.ofMillis(settings.auraInterval() * 50L), settings.auraKnockbackStrength());
        }
    }

    @Nested
    @DisplayName("describe")
    class Describe {

        @Test
        @DisplayName("names all five items as what it does")
        void describesItself() {
            assertThat(service.describe()).contains("five").contains("sponsor").contains("combat items");
        }
    }

    @Test
    @DisplayName("settings can be swapped in without recreating the service")
    void settingsSwap() {
        List<HungerGamesSettings> seen = new ArrayList<>();
        seen.add(HungerGamesSettings.DEFAULTS);

        service.settings(HungerGamesSettings.DEFAULTS);

        // Nothing here reads a setting yet (see the class note on tuned numbers being constants rather than
        // config), but the service must still accept a reload without throwing — every IHungerGamesService
        // promises that much.
        assertThat(seen).hasSize(1);
    }
}
