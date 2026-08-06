package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.BorderConflict;
import de.raindancer.modules.hungergames.model.BorderMath;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderResolution;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.model.BorderTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Border arithmetic: edge speed, conflict detection, and the computed effect of every resolution option.
 */
class BorderMathTest {

    private static final double MAX_EDGE_SPEED = 2.5;

    private static BorderSettings settings(BorderPhaseConfig... phases) {
        return new BorderSettings(2500, 100, MAX_EDGE_SPEED, List.of(phases));
    }

    @Test
    @DisplayName("Edge speed is half the diameter difference per second")
    void edgeSpeedFormula() {
        // 2500 -> 1500 over 200s: a diameter change of 1000 is 500 blocks per edge -> 2.5 b/s
        assertEquals(2.5, BorderMath.edgeSpeed(2500, 1500, 200), 1e-9);
        assertEquals(Duration.ofSeconds(200), BorderMath.durationFor(2500, 1500, 2.5));
    }

    @Test
    @DisplayName("A configuration within the 2.5 b/s ceiling has no conflicts")
    void validConfigHasNoConflicts() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofDuration(BorderTrigger.atTime(Duration.ofMinutes(20)),
                        1500, Duration.ofSeconds(200)), // exactly 2.5 b/s
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.aliveBelow(4), 100, 1.0));

        assertTrue(BorderMath.validate(s, Optional.of(Duration.ofMinutes(180))).isEmpty());
    }

    @Test
    @DisplayName("A too-fast DURATION phase conflicts, with the correct implied speed")
    void tooFastDurationPhaseConflicts() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofDuration(BorderTrigger.atTime(Duration.ofMinutes(10)),
                        500, Duration.ofSeconds(100))); // (2500-500)/2/100 = 10 b/s

        List<BorderConflict> conflicts = BorderMath.validate(s, Optional.empty());

        assertEquals(1, conflicts.size());
        BorderConflict conflict = conflicts.get(0);
        assertEquals(BorderConflict.Type.SPEED_EXCEEDS_MAX, conflict.type());
        assertEquals(10.0, conflict.impliedSpeed(), 1e-9);
        assertEquals(MAX_EDGE_SPEED, conflict.limit(), 1e-9);
    }

    @Test
    @DisplayName("Resolution options for a speed conflict compute the right effects")
    void speedConflictResolutions() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofDuration(BorderTrigger.atTime(Duration.ofMinutes(10)),
                        500, Duration.ofSeconds(100)));
        BorderConflict conflict = BorderMath.validate(s, Optional.empty()).get(0);

        List<BorderResolution> options = BorderMath.resolutions(s, conflict, Optional.empty());

        // Option 1: adjust the duration -> (2500-500)/2/2.5 = 400s
        BorderResolution.AdjustDuration adjustDuration = options.stream()
                .filter(BorderResolution.AdjustDuration.class::isInstance)
                .map(BorderResolution.AdjustDuration.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Duration.ofSeconds(400), adjustDuration.newDuration());
        assertEquals(MAX_EDGE_SPEED, adjustDuration.resultingSpeed(), 1e-9);

        // Option 2: adjust the target -> 2500 - 2*2.5*100 = 2000
        BorderResolution.AdjustTarget adjustTarget = options.stream()
                .filter(BorderResolution.AdjustTarget.class::isInstance)
                .map(BorderResolution.AdjustTarget.class::cast)
                .findFirst().orElseThrow();
        assertEquals(2000, adjustTarget.newTarget(), 1e-9);

        // Option 3: use the speed as a ceiling -> 400s at 2.5 b/s
        BorderResolution.UseSpeedAsMax asMax = options.stream()
                .filter(BorderResolution.UseSpeedAsMax.class::isInstance)
                .map(BorderResolution.UseSpeedAsMax.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Duration.ofSeconds(400), asMax.effectiveDuration());

        // Discard is always the last option
        assertInstanceOf(BorderResolution.Discard.class, options.get(options.size() - 1));
    }

    @Test
    @DisplayName("Applying AdjustDuration resolves the conflict — nothing changes before it is confirmed")
    void applyAdjustDurationResolvesConflict() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofDuration(BorderTrigger.atTime(Duration.ofMinutes(10)),
                        500, Duration.ofSeconds(100)));
        BorderConflict conflict = BorderMath.validate(s, Optional.empty()).get(0);
        BorderResolution.AdjustDuration fix = BorderMath.resolutions(s, conflict, Optional.empty()).stream()
                .filter(BorderResolution.AdjustDuration.class::isInstance)
                .map(BorderResolution.AdjustDuration.class::cast)
                .findFirst().orElseThrow();

        // Before confirmation: the original is unchanged
        assertEquals(Duration.ofSeconds(100), s.phases().get(0).duration().orElseThrow());

        BorderMath.ApplyResult applied = BorderMath.apply(s, 0, fix);

        assertTrue(BorderMath.validate(applied.settings(), Optional.empty()).isEmpty());
        assertEquals(Duration.ofSeconds(400), applied.settings().phases().get(0).duration().orElseThrow());
        assertTrue(applied.newGameDuration().isEmpty());
    }

    @Test
    @DisplayName("A target below the minimum size gives TARGET_BELOW_MINIMUM with an AdjustTarget option")
    void targetBelowMinimum() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.atTime(Duration.ofMinutes(10)), 50, 1.0));

        List<BorderConflict> conflicts = BorderMath.validate(s, Optional.empty());
        assertEquals(1, conflicts.size());
        assertEquals(BorderConflict.Type.TARGET_BELOW_MINIMUM, conflicts.get(0).type());

        BorderResolution.AdjustTarget fix = BorderMath.resolutions(s, conflicts.get(0), Optional.empty()).stream()
                .filter(BorderResolution.AdjustTarget.class::isInstance)
                .map(BorderResolution.AdjustTarget.class::cast)
                .findFirst().orElseThrow();
        assertEquals(100, fix.newTarget(), 1e-9);
    }

    @Test
    @DisplayName("A phase that overruns the game length gives ShiftStart/AdjustDuration/AdjustGameTime")
    void exceedsGameTime() {
        // Trigger at 170min, duration 400s -> ends around 176.7min, past a 175min game
        BorderSettings s = settings(
                BorderPhaseConfig.ofDuration(BorderTrigger.atTime(Duration.ofMinutes(170)),
                        500, Duration.ofSeconds(400)));
        Optional<Duration> game = Optional.of(Duration.ofMinutes(175));

        List<BorderConflict> conflicts = BorderMath.validate(s, game);
        assertEquals(1, conflicts.size());
        assertEquals(BorderConflict.Type.EXCEEDS_GAME_TIME, conflicts.get(0).type());

        List<BorderResolution> options = BorderMath.resolutions(s, conflicts.get(0), game);

        BorderResolution.ShiftStart shift = options.stream()
                .filter(BorderResolution.ShiftStart.class::isInstance)
                .map(BorderResolution.ShiftStart.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Duration.ofMinutes(175).minus(Duration.ofSeconds(400)), shift.newStart());

        BorderResolution.AdjustGameTime extend = options.stream()
                .filter(BorderResolution.AdjustGameTime.class::isInstance)
                .map(BorderResolution.AdjustGameTime.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Duration.ofMinutes(170).plus(Duration.ofSeconds(400)), extend.newGameDuration());

        // AdjustGameTime changes only the game length, not the border settings
        BorderMath.ApplyResult applied = BorderMath.apply(s, 0, extend);
        assertEquals(s, applied.settings());
        assertEquals(extend.newGameDuration(), applied.newGameDuration().orElseThrow());
    }

    @Test
    @DisplayName("Out-of-order time triggers give PHASES_OUT_OF_ORDER")
    void phasesOutOfOrder() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.atTime(Duration.ofMinutes(30)), 1500, 1.0),
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.atTime(Duration.ofMinutes(10)), 800, 1.0));

        List<BorderConflict> conflicts = BorderMath.validate(s, Optional.empty());
        assertTrue(conflicts.stream().anyMatch(c -> c.type() == BorderConflict.Type.PHASES_OUT_OF_ORDER));
    }

    @Test
    @DisplayName("A growing target gives TARGET_NOT_SHRINKING")
    void growingTargetDetected() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.atTime(Duration.ofMinutes(10)), 3000, 1.0));

        List<BorderConflict> conflicts = BorderMath.validate(s, Optional.empty());
        assertEquals(BorderConflict.Type.TARGET_NOT_SHRINKING, conflicts.get(0).type());
    }

    @Test
    @DisplayName("MAX_SPEED without a time anchor is clamped and never conflicts on speed")
    void maxSpeedModeNeverConflicts() {
        BorderSettings s = settings(
                BorderPhaseConfig.ofMaxSpeed(BorderTrigger.atTime(Duration.ofMinutes(10)), 500, 99.0));

        assertTrue(BorderMath.validate(s, Optional.empty()).isEmpty());
        assertEquals(MAX_EDGE_SPEED, BorderMath.impliedSpeed(s, 0), 1e-9);
    }

    @Test
    @DisplayName("MAX_SPEED with an achievable preferred duration runs slower than the ceiling")
    void maxSpeedWithAchievablePreferredDuration() {
        // 2500 -> 1500 over 1000s needs 0.5 b/s, well under the 2.5 ceiling
        BorderSettings s = settings(
                BorderPhaseConfig.ofMaxSpeed(BorderTrigger.atTime(Duration.ofMinutes(10)),
                        1500, 2.5, Duration.ofSeconds(1000)));

        assertTrue(BorderMath.validate(s, Optional.empty()).isEmpty());
        assertEquals(0.5, BorderMath.impliedSpeed(s, 0), 1e-9);
        assertEquals(Duration.ofSeconds(1000), BorderMath.effectiveDuration(s, 0));
    }

    @Test
    @DisplayName("MAX_SPEED with an unreachable preferred duration gives a SPEED_EXCEEDS_MAX conflict")
    void maxSpeedWithUnachievablePreferredDurationConflicts() {
        // 2500 -> 500 over 100s would need 10 b/s, the ceiling is 2.5 -> conflict
        BorderSettings s = settings(
                BorderPhaseConfig.ofMaxSpeed(BorderTrigger.atTime(Duration.ofMinutes(10)),
                        500, 2.5, Duration.ofSeconds(100)));

        List<BorderConflict> conflicts = BorderMath.validate(s, Optional.empty());
        assertEquals(1, conflicts.size());
        assertEquals(BorderConflict.Type.SPEED_EXCEEDS_MAX, conflicts.get(0).type());
        assertEquals(10.0, conflicts.get(0).impliedSpeed(), 1e-9);
        assertEquals(2.5, conflicts.get(0).limit(), 1e-9);

        // Resolution options: adjusting the duration and using the speed as a ceiling are both present
        List<BorderResolution> options = BorderMath.resolutions(s, conflicts.get(0), Optional.empty());
        assertTrue(options.stream().anyMatch(BorderResolution.AdjustDuration.class::isInstance));
        assertTrue(options.stream().anyMatch(BorderResolution.UseSpeedAsMax.class::isInstance));
    }

    @Test
    @DisplayName("Resolutions use the phase's own cap when it is tighter than the global limit")
    void resolutionsUsePhaseCapWhenLower() {
        // Phase allows at most 1.0 b/s (global 2.5); the 100s anchor would need 10 b/s
        BorderSettings s = settings(
                BorderPhaseConfig.ofMaxSpeed(BorderTrigger.atTime(Duration.ofMinutes(10)),
                        500, 1.0, Duration.ofSeconds(100)));
        BorderConflict conflict = BorderMath.validate(s, Optional.empty()).get(0);
        assertEquals(1.0, conflict.limit(), 1e-9, "validation reports the phase's own cap");

        BorderResolution.AdjustDuration fix = BorderMath.resolutions(s, conflict, Optional.empty()).stream()
                .filter(BorderResolution.AdjustDuration.class::isInstance)
                .map(BorderResolution.AdjustDuration.class::cast)
                .findFirst().orElseThrow();
        // (2500-500)/2/1.0 = 1000s -- using the phase's cap, not the global 2.5
        assertEquals(Duration.ofSeconds(1000), fix.newDuration());
        assertEquals(1.0, fix.resultingSpeed(), 1e-9);

        // Applied, the configuration is conflict-free
        BorderMath.ApplyResult applied = BorderMath.apply(s, 0, fix);
        assertTrue(BorderMath.validate(applied.settings(), Optional.empty()).isEmpty(),
                "the conflict must not reappear after its own resolution");
    }
}
