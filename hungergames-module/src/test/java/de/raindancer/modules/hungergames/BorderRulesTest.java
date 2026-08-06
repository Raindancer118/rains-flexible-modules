package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.model.BorderTrigger;
import de.raindancer.modules.hungergames.rules.BorderRules;
import de.raindancer.modules.hungergames.rules.BorderRules.ShrinkCommand;
import de.raindancer.modules.hungergames.rules.BorderRules.TickResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Border runtime behaviour: time and tribute-count triggers, the minimum size floor, phases running in
 * sequence, and picking back up at a persisted phase index after a restart.
 *
 * <p>{@link BorderRules} holds no state of its own — see the class note — so unlike the source engine this
 * test carries the "next phase index" itself between ticks, exactly as {@code service.BorderService} will
 * once it exists: read the index from the session snapshot, tick, persist whatever index comes back.
 */
class BorderRulesTest {

    private final BorderRules rules = new BorderRules();

    private static BorderSettings twoPhases() {
        return new BorderSettings(2500, 100, 2.5, List.of(
                BorderPhaseConfig.ofDuration(BorderTrigger.atTime(Duration.ofMinutes(20)),
                        1500, Duration.ofSeconds(400)),
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.either(Duration.ofMinutes(60), 4),
                        100, 1.0)));
    }

    @Test
    @DisplayName("A time trigger fires exactly once, not before")
    void timeTriggerFiresOnce() {
        BorderSettings settings = twoPhases();

        TickResult before = rules.tick(settings, 0, Duration.ofMinutes(19), 10, 2500);
        assertTrue(before.command().isEmpty());

        TickResult fired = rules.tick(settings, before.nextPhaseIndex(), Duration.ofMinutes(20), 10, 2500);
        ShrinkCommand cmd = fired.command().orElseThrow();
        assertEquals(1500, cmd.targetSize(), 1e-9);
        assertEquals(Duration.ofSeconds(400), cmd.duration());
        assertEquals(1.25, cmd.effectiveSpeed(), 1e-9); // (2500-1500)/2/400s
        assertEquals(1, fired.nextPhaseIndex());

        // Same conditions again -> the phase has moved on and does not fire a second time
        TickResult again = rules.tick(settings, fired.nextPhaseIndex(), Duration.ofMinutes(21), 10, 2100);
        assertTrue(again.command().isEmpty());
    }

    @Test
    @DisplayName("A tribute-count trigger fires ahead of the time condition")
    void aliveTriggerFires() {
        BorderSettings settings = twoPhases();
        TickResult phase1 = rules.tick(settings, 0, Duration.ofMinutes(20), 10, 2500); // phase 1 fired

        // Phase 2: aliveBelow 4 OR 60min -- with 3 alive it fires immediately
        TickResult phase2 = rules.tick(settings, phase1.nextPhaseIndex(), Duration.ofMinutes(25), 3, 1500);
        ShrinkCommand cmd = phase2.command().orElseThrow();
        assertEquals(100, cmd.targetSize(), 1e-9);
        // FIXED_SPEED 1.0: (1500-100)/2/1.0 = 700s
        assertEquals(Duration.ofSeconds(700), cmd.duration());
        assertTrue(rules.isFinished(settings, phase2.nextPhaseIndex()));
    }

    @Test
    @DisplayName("The minimum size is enforced hard at runtime")
    void minimumSizeEnforced() {
        BorderSettings settings = new BorderSettings(2500, 400, 2.5, List.of(
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.atTime(Duration.ZERO), 100, 1.0)));

        TickResult result = rules.tick(settings, 0, Duration.ZERO, 10, 2500);
        assertEquals(400, result.command().orElseThrow().targetSize(), 1e-9, "target is raised to minimumSize");
    }

    @Test
    @DisplayName("A phase with nothing left to shrink is skipped")
    void noShrinkWhenAlreadySmaller() {
        BorderSettings settings = new BorderSettings(2500, 100, 2.5, List.of(
                BorderPhaseConfig.ofFixedSpeed(BorderTrigger.atTime(Duration.ZERO), 2000, 1.0)));

        // The border is already smaller than the target (e.g. an admin already shrank it by hand).
        TickResult result = rules.tick(settings, 0, Duration.ZERO, 10, 1800);
        assertTrue(result.command().isEmpty());
        assertTrue(rules.isFinished(settings, result.nextPhaseIndex()), "the phase still counts as spent");
    }

    @Test
    @DisplayName("Picking back up at a persisted phase index resumes correctly")
    void restoreContinuesAtPersistedIndex() {
        TickResult fired = rules.tick(twoPhases(), 0, Duration.ofMinutes(20), 10, 2500);
        int persisted = fired.nextPhaseIndex();
        assertEquals(1, persisted);

        assertFalse(rules.isFinished(twoPhases(), persisted));
        // Phase 1 does not fire again; phase 2 waits for its own condition.
        assertTrue(rules.tick(twoPhases(), persisted, Duration.ofMinutes(30), 10, 1500).command().isEmpty());
        assertTrue(rules.tick(twoPhases(), persisted, Duration.ofMinutes(60), 10, 1500).command().isPresent());
    }

    @Test
    @DisplayName("A MAX_SPEED phase without an anchor shrinks at the fairness ceiling")
    void maxSpeedClampedAtRuntime() {
        BorderSettings settings = new BorderSettings(2500, 100, 2.5, List.of(
                BorderPhaseConfig.ofMaxSpeed(BorderTrigger.atTime(Duration.ZERO), 500, 50.0)));

        ShrinkCommand cmd = rules.tick(settings, 0, Duration.ZERO, 10, 2500).command().orElseThrow();
        assertEquals(2.5, cmd.effectiveSpeed(), 1e-9);
        assertEquals(Duration.ofSeconds(400), cmd.duration()); // (2500-500)/2/2.5
    }

    @Test
    @DisplayName("A MAX_SPEED phase with a time anchor keeps the anchor while it is reachable")
    void maxSpeedWithPreferredDurationAtRuntime() {
        BorderSettings settings = new BorderSettings(2500, 100, 2.5, List.of(
                BorderPhaseConfig.ofMaxSpeed(BorderTrigger.atTime(Duration.ZERO),
                        1500, 2.5, Duration.ofSeconds(1000))));

        ShrinkCommand cmd = rules.tick(settings, 0, Duration.ZERO, 10, 2500).command().orElseThrow();
        assertEquals(0.5, cmd.effectiveSpeed(), 1e-9); // the preferred pace, not the ceiling
        assertEquals(Duration.ofSeconds(1000), cmd.duration());
    }
}
