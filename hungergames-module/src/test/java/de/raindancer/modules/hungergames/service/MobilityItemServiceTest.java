package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
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
import static org.mockito.Mockito.mock;

/**
 * Drives the four predicates {@link MobilityItemService#register()} hands to Core, without a running
 * server: fake seams record what they were asked for and hand back a scripted answer, the same approach as
 * {@code TeamPresentationServiceTest}'s {@code RecordingHelmets}.
 */
@ExtendWith(MockitoExtension.class)
class MobilityItemServiceTest {

    /** Records every call it received, and answers whatever the test told it to. */
    private static final class RecordingFlight implements MobilityItemService.Flight {
        final List<ItemUse> uses = new ArrayList<>();
        Duration lastForHowLong;
        Duration lastWarnBefore;
        boolean answer = true;

        @Override
        public boolean fly(ItemUse use, Duration forHowLong, Duration warnBefore) {
            uses.add(use);
            lastForHowLong = forHowLong;
            lastWarnBefore = warnBefore;
            return answer;
        }
    }

    private static final class RecordingGrappling implements MobilityItemService.Grappling {
        final List<ItemUse> uses = new ArrayList<>();
        double lastRange;
        double lastPower;
        boolean answer = true;

        @Override
        public boolean pullTowardsTarget(ItemUse use, double range, double power) {
            uses.add(use);
            lastRange = range;
            lastPower = power;
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

    @Mock
    private HungerGamesSettings settings;

    private GamePhase phase;
    private RecordingFlight flight;
    private RecordingGrappling grappling;
    private RecordingRepulsion repulsion;
    private RecordingLaunching launching;
    private MobilityItemService service;

    @BeforeEach
    void setUp() {
        phase = GamePhase.RUNNING;
        flight = new RecordingFlight();
        grappling = new RecordingGrappling();
        repulsion = new RecordingRepulsion();
        launching = new RecordingLaunching();
        service = new MobilityItemService(abilities, items, () -> phase, flight, grappling, repulsion,
                launching, settings);
    }

    private static ItemUse use(String ability) {
        return new ItemUse(UUID.randomUUID(), ability, ItemTrigger.RIGHT_CLICK, null);
    }

    @Nested
    @DisplayName("Hermes' boots")
    class HermesBoots {

        @Test
        @DisplayName("flies for the configured duration with the configured warning")
        void flysForTheConfiguredDuration() {
            boolean result = service.flyLikeHermes(use(MobilityItemService.HERMES_BOOTS));

            assertThat(result).isTrue();
            assertThat(flight.lastForHowLong).isEqualTo(MobilityItemService.HERMES_FLIGHT_DURATION);
            assertThat(flight.lastWarnBefore).isEqualTo(MobilityItemService.HERMES_WARNING_DURATION);
        }

        @Test
        @DisplayName("does nothing outside a running round")
        void doesNothingOutsideARunningRound() {
            phase = GamePhase.LOBBY;

            boolean result = service.flyLikeHermes(use(MobilityItemService.HERMES_BOOTS));

            assertThat(result).isFalse();
            assertThat(flight.uses).isEmpty();
        }

        @Test
        @DisplayName("declines when the seam refuses, e.g. the holder can already fly")
        void declinesWhenTheSeamRefuses() {
            flight.answer = false;

            boolean result = service.flyLikeHermes(use(MobilityItemService.HERMES_BOOTS));

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("the grappling hook")
    class GrapplingHook {

        @Test
        @DisplayName("pulls towards a target using the configured range and power")
        void pullsTowardsATargetUsingConfiguredValues() {
            boolean result = service.fireTheGrapplingHook(use(MobilityItemService.GRAPPLING_HOOK));

            assertThat(result).isTrue();
            assertThat(grappling.lastRange).isEqualTo(MobilityItemService.GRAPPLING_RANGE);
            assertThat(grappling.lastPower).isEqualTo(MobilityItemService.GRAPPLING_POWER);
        }

        @Test
        @DisplayName("does nothing outside a running round")
        void doesNothingOutsideARunningRound() {
            phase = GamePhase.READY;

            boolean result = service.fireTheGrapplingHook(use(MobilityItemService.GRAPPLING_HOOK));

            assertThat(result).isFalse();
            assertThat(grappling.uses).isEmpty();
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
            assertThat(repulsion.lastRadius).isEqualTo(MobilityItemService.REPULSE_RADIUS);
            assertThat(repulsion.lastVelocity).isEqualTo(MobilityItemService.REPULSE_VELOCITY);
            assertThat(repulsion.lastSlowFor).isEqualTo(MobilityItemService.REPULSE_SLOW_DURATION);
        }

        @Test
        @DisplayName("does nothing outside a running round")
        void doesNothingOutsideARunningRound() {
            phase = GamePhase.FINISHED;

            boolean result = service.unleashRepulse(use(MobilityItemService.REPULSE));

            assertThat(result).isFalse();
            assertThat(repulsion.uses).isEmpty();
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
        @DisplayName("launches forwards using the configured power and soft landing")
        void launchesUsingConfiguredValues() {
            boolean result = service.leap(use(MobilityItemService.LEAP));

            assertThat(result).isTrue();
            assertThat(launching.lastPower).isEqualTo(MobilityItemService.LEAP_POWER);
            assertThat(launching.lastSoftLanding).isEqualTo(MobilityItemService.LEAP_SOFT_LANDING);
        }

        @Test
        @DisplayName("does nothing outside a running round")
        void doesNothingOutsideARunningRound() {
            phase = GamePhase.STARTUP;

            boolean result = service.leap(use(MobilityItemService.LEAP));

            assertThat(result).isFalse();
            assertThat(launching.uses).isEmpty();
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
    @DisplayName("phase checking")
    class PhaseChecking {

        @Test
        @DisplayName("duringARound is true only while the round is RUNNING")
        void duringARoundIsTrueOnlyWhileRunning() {
            for (GamePhase candidate : GamePhase.values()) {
                phase = candidate;
                assertThat(service.duringARound())
                        .as("phase " + candidate)
                        .isEqualTo(candidate == GamePhase.RUNNING);
            }
        }
    }

    @Test
    @DisplayName("settings(...) swaps in a new settings instance without throwing")
    void settingsSwapsInANewInstance() {
        HungerGamesSettings replacement = mock(HungerGamesSettings.class);

        service.settings(replacement);

        // Nothing observable yet reads settings — this only proves the seam accepts a reload without
        // blowing up, which is what IHungerGamesService promises every service does.
        assertThat(service).isNotNull();
    }
}
