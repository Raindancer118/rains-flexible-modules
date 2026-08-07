package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.Tweak;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the three predicates {@link MobilityItemService#register()} hands to Core, without a running
 * server: fake seams record what they were asked for and hand back a scripted answer, the same approach as
 * {@code TeamPresentationServiceTest}'s {@code RecordingHelmets}.
 *
 * <p>Every number below comes from {@link #settings}, read live on each use — the regression this file
 * guards is the one found in testing: {@code MobilityItemService} held a settings field nobody ever read,
 * so a server's {@code items.grappling.range} and its siblings' config keys had no effect on the game at
 * all. {@link HungerGamesSettings#DEFAULTS} rather than a mock, for the same reason
 * {@code CombatItemServiceTest} uses it: a mocked settings object answers every unstubbed method with a
 * silent zero, which would make a bug that reads {@code 0} indistinguishable from a passing test.
 *
 * <p>Hermes' boots left this class entirely — see {@code HermesBootsServiceTest} — once they stopped being
 * a right-click ability and became worn equipment with a flight budget.
 */
@ExtendWith(MockitoExtension.class)
class MobilityItemServiceTest {

    /** Records every call it received, and answers whatever the test told it to. */
    private static final class RecordingGrappling implements MobilityItemService.Grappling {
        final List<ItemUse> uses = new ArrayList<>();
        double lastRange;
        double lastSpeed;
        Duration lastMaxDuration;
        boolean answer = true;

        @Override
        public boolean pullTowardsTarget(ItemUse use, double range, double speed, Duration maxDuration) {
            uses.add(use);
            lastRange = range;
            lastSpeed = speed;
            lastMaxDuration = maxDuration;
            return answer;
        }
    }

    private static final class RecordingRepulsion implements MobilityItemService.Repulsion {
        final List<ItemUse> uses = new ArrayList<>();
        double lastRadius;
        double lastVelocity;
        Duration lastSlowFor;
        boolean answer = true;

        @Override
        public boolean shove(ItemUse use, double radius, double velocity, Duration slowFor) {
            uses.add(use);
            lastRadius = radius;
            lastVelocity = velocity;
            lastSlowFor = slowFor;
            return answer;
        }
    }

    private static final class RecordingLaunching implements MobilityItemService.Launching {
        final List<ItemUse> uses = new ArrayList<>();
        double lastPower;
        Duration lastSoftLanding;
        boolean answer = true;

        @Override
        public boolean launchForwards(ItemUse use, double power, Duration softLanding) {
            uses.add(use);
            lastPower = power;
            lastSoftLanding = softLanding;
            return answer;
        }
    }

    @Mock
    private ItemAbilities abilities;

    @Mock
    private de.raindancer.core.content.items.CustomItems items;

    private final HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    private RecordingGrappling grappling;
    private RecordingRepulsion repulsion;
    private RecordingLaunching launching;
    private MobilityItemService service;

    @BeforeEach
    void setUp() {
        grappling = new RecordingGrappling();
        repulsion = new RecordingRepulsion();
        launching = new RecordingLaunching();
        service = new MobilityItemService(abilities, items, grappling, repulsion, launching, settings);
    }

    private static ItemUse use(String ability) {
        return new ItemUse(UUID.randomUUID(), ability, ItemTrigger.RIGHT_CLICK, null);
    }

    @Nested
    @DisplayName("the grappling hook")
    class GrapplingHook {

        @Test
        @DisplayName("pulls towards a target using the configured range and speed")
        void pullsTowardsATargetUsingConfiguredValues() {
            boolean result = service.fireTheGrapplingHook(use(MobilityItemService.GRAPPLING_HOOK));

            assertThat(result).isTrue();
            assertThat(grappling.lastRange).isEqualTo(settings.grapplingRange());
            assertThat(grappling.lastSpeed).isEqualTo(settings.grapplingPowerStrength());
            assertThat(grappling.lastMaxDuration).isEqualTo(MobilityItemService.GRAPPLING_MAX_PULL_DURATION);
        }

        @Test
        @DisplayName("a server that tuned the range is actually honoured")
        void theRangeIsTheServersOwn() {
            service.settings(Tweak.of(settings, "grapplingRange", 20));

            service.fireTheGrapplingHook(use(MobilityItemService.GRAPPLING_HOOK));

            assertThat(grappling.lastRange).isEqualTo(20.0);
        }

        @Test
        @DisplayName("still works with no round on at all")
        void worksOutsideARound() {
            boolean result = service.fireTheGrapplingHook(use(MobilityItemService.GRAPPLING_HOOK));

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("aimed at the sky, declines and must not burn its cooldown")
        void aimedAtTheSkyDeclines() {
            grappling.answer = false;

            boolean result = service.fireTheGrapplingHook(use(MobilityItemService.GRAPPLING_HOOK));

            assertThat(result)
                    .as("a decline here is what tells Core not to spend the cooldown")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("repulse")
    class Repulse {

        @Test
        @DisplayName("shoves everybody nearby using the configured radius, velocity and slow duration")
        void shovesUsingConfiguredValues() {
            boolean result = service.unleashRepulse(use(MobilityItemService.REPULSE));

            assertThat(result).isTrue();
            assertThat(repulsion.lastRadius).isEqualTo(settings.repulseRadius());
            assertThat(repulsion.lastVelocity).isEqualTo(settings.repulseStrengthMultiplier());
            assertThat(repulsion.lastSlowFor).isEqualTo(Duration.ofSeconds(settings.repulseSlowSeconds()));
        }

        @Test
        @DisplayName("a server that tuned the radius is actually honoured")
        void theRadiusIsTheServersOwn() {
            service.settings(Tweak.of(settings, "repulseRadius", 12));

            service.unleashRepulse(use(MobilityItemService.REPULSE));

            assertThat(repulsion.lastRadius).isEqualTo(12.0);
        }

        @Test
        @DisplayName("still works with no round on at all")
        void worksOutsideARound() {
            boolean result = service.unleashRepulse(use(MobilityItemService.REPULSE));

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("declines when the seam refuses")
        void declinesWhenTheSeamRefuses() {
            repulsion.answer = false;

            boolean result = service.unleashRepulse(use(MobilityItemService.REPULSE));

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("leap")
    class Leap {

        @Test
        @DisplayName("launches forwards using the configured power and the source's soft landing")
        void launchesUsingConfiguredValues() {
            boolean result = service.leap(use(MobilityItemService.LEAP));

            assertThat(result).isTrue();
            assertThat(launching.lastPower).isEqualTo(settings.leapPowerStrength());
            assertThat(launching.lastSoftLanding).isEqualTo(MobilityItemService.LEAP_SOFT_LANDING);
        }

        @Test
        @DisplayName("a server that tuned the power is actually honoured")
        void thePowerIsTheServersOwn() {
            service.settings(Tweak.of(settings, "leapPower", 30));

            service.leap(use(MobilityItemService.LEAP));

            assertThat(launching.lastPower).isEqualTo(3.0);
        }

        @Test
        @DisplayName("still works with no round on at all")
        void worksOutsideARound() {
            boolean result = service.leap(use(MobilityItemService.LEAP));

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("declines when the seam refuses")
        void declinesWhenTheSeamRefuses() {
            launching.answer = false;

            boolean result = service.leap(use(MobilityItemService.LEAP));

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("cooldowns default to off, matching the source")
    class Cooldowns {

        @Test
        @DisplayName("every one of the three abilities registers with no cooldown by default")
        void defaultToZero() {
            // The source had none of these — every one of these three items was single-use and gone the
            // moment it fired, so there was nothing to cool down. An earlier pass of this port gave all
            // three a fixed, un-configurable cooldown anyway. This is the regression test for undoing that:
            // an upgrading server that never touches the new keys gets exactly the source's behaviour.
            service.register();

            org.mockito.ArgumentCaptor<de.raindancer.core.content.items.ItemAbility> captor =
                    org.mockito.ArgumentCaptor.forClass(de.raindancer.core.content.items.ItemAbility.class);
            org.mockito.Mockito.verify(abilities, org.mockito.Mockito.times(3)).register(captor.capture());

            assertThat(captor.getAllValues())
                    .as("cooldownMillis() is null exactly when an ability has no cooldown — see "
                            + "ItemAbility.Builder.cooldown()")
                    .allSatisfy(ability -> assertThat(ability.cooldownMillis()).isNull());
        }

        @Test
        @DisplayName("a server that set one is actually honoured, at the moment it registers")
        void aConfiguredCooldownIsHonoured() {
            MobilityItemService tuned = new MobilityItemService(abilities, items,
                    grappling, repulsion, launching, Tweak.of(settings, "leapCooldownSeconds", 6));

            tuned.register();

            org.mockito.ArgumentCaptor<de.raindancer.core.content.items.ItemAbility> captor =
                    org.mockito.ArgumentCaptor.forClass(de.raindancer.core.content.items.ItemAbility.class);
            org.mockito.Mockito.verify(abilities, org.mockito.Mockito.times(3)).register(captor.capture());

            assertThat(captor.getAllValues().stream()
                            .filter(ability -> ability.id().equals(MobilityItemService.LEAP))
                            .findFirst().orElseThrow().cooldownMillis())
                    .isEqualTo(6_000L);
        }
    }

    @Test
    @DisplayName("settings(...) swaps in a new settings instance without throwing")
    void settingsSwapsInANewInstance() {
        HungerGamesSettings replacement = Tweak.of(settings, "leapPower", 25);

        service.settings(replacement);
        service.leap(use(MobilityItemService.LEAP));

        assertThat(launching.lastPower).isEqualTo(2.5);
    }
}
