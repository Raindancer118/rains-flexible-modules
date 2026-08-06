package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.BorderConflict;
import de.raindancer.modules.hungergames.model.BorderMath;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.store.BorderPhaseStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the border still finishes inside the round after the speed ceiling was halved.
 *
 * <h2>Why this exists</h2>
 * {@code border.max-edge-speed} was 2.5 blocks per second and is now 1.25 — see {@link BorderOutrunTest}
 * for the arithmetic that set it. Halving a ceiling every phase is capped by is not a free change: a
 * phase that used to reach its target in ten minutes now takes twenty, and if the phases are triggered
 * late enough, the border is still closing when the round is called on time. Nothing would crash. The
 * round would simply end with the arena bigger than it was configured to finish at, and the endgame the
 * shrink exists to force would never happen.
 *
 * <p>{@link BorderMath#validate} already reports that as {@code EXCEEDS_GAME_TIME}. This asks it about
 * the configuration a real server actually has, which the module's own defaults cannot answer: a fresh
 * install ships <em>no</em> phases at all, so the defaults are trivially fine and prove nothing. The plan
 * checked here is the one in the v1 plugin's shipped config, which is what an upgrading server arrives
 * with and therefore what the new ceiling has to be safe against.
 *
 * <p>Not advisory. Whether the ceiling is the right speed is a judgement (see {@link BorderOutrunTest});
 * whether the border can reach its target before the clock runs out is arithmetic, and getting it wrong
 * breaks a round rather than tuning one.
 */
class BorderFitsTheRoundTest {

    /** The default round length, from the settings rather than written out again. */
    private static final Duration ROUND = HungerGamesSettings.DEFAULTS.roundDuration();

    /** The ceiling under test, likewise. */
    private static final double CEILING = HungerGamesSettings.DEFAULTS.borderEdgeSpeed();

    /**
     * The v1 plugin's shipped phase plan, in the syntax a {@code border-phases.yml} is written in.
     *
     * <p>Each carries {@code max:2.5} of its own, which is the point: a per-phase cap does not escape the
     * global one — {@code BorderMath.speedCap} takes the smaller of the two — so these phases run at 1.25
     * now whether or not anybody edits the file. An upgrading server changes nothing and gets a slower
     * border, which is exactly the case that has to fit.
     */
    private static final List<String> V1_PHASES = List.of(
            "50% -> 1000 @ max:2.5",
            "80% -> 200 @ max:2.5");

    private static BorderSettings settings() {
        List<BorderPhaseConfig> phases = V1_PHASES.stream()
                .map(line -> BorderPhaseStore.parse(line, ROUND))
                .toList();
        return new BorderSettings(
                HungerGamesSettings.DEFAULTS.borderInitialSize(),
                HungerGamesSettings.DEFAULTS.borderFloor(),
                CEILING,
                phases);
    }

    @Test
    @DisplayName("the v1 phase plan still fits in the default round at the new ceiling")
    void theBorderStillGetsThereInTime() {
        List<BorderConflict> conflicts = BorderMath.validate(settings(), Optional.of(ROUND));

        assertThat(conflicts)
                .as("""
                        At %.2f blocks per second the border cannot finish what the phases ask of it \
                        before the %d-minute round is called on time. Nothing crashes: the round simply \
                        ends with the arena larger than it was configured to finish at, and the endgame \
                        the shrink exists to force never arrives. Either the ceiling goes back up or the \
                        phases have to trigger earlier.""",
                        CEILING, ROUND.toMinutes())
                .isEmpty();
    }

    @Test
    @DisplayName("the whole shrink is done with a good half hour of the round left")
    void thereIsMarginRatherThanAHairsBreadth() {
        BorderSettings settings = settings();

        Duration lastPhaseEnds = Duration.ZERO;
        for (int phase = 0; phase < settings.phases().size(); phase++) {
            Duration triggersAt = settings.phases().get(phase).trigger().time().orElseThrow();
            Duration ends = triggersAt.plus(BorderMath.effectiveDuration(settings, phase));
            if (ends.compareTo(lastPhaseEnds) > 0) {
                lastPhaseEnds = ends;
            }
        }

        // "It fits" and "it fits with room" are different claims, and only the second one survives
        // somebody lengthening a phase or moving a trigger. Fitting exactly is a configuration one edit
        // away from not fitting, and the edit that breaks it looks harmless.
        Duration spare = ROUND.minus(lastPhaseEnds);
        assertThat(spare)
                .as("the border finishes shrinking at %d min of a %d min round, leaving %d min. At the "
                        + "old 2.5 b/s ceiling it finished at %d min",
                        lastPhaseEnds.toMinutes(), ROUND.toMinutes(), spare.toMinutes(),
                        finishesAt(2.5D).toMinutes())
                .isGreaterThanOrEqualTo(Duration.ofMinutes(20));
    }

    /** When the last phase would finish at some other ceiling — for the comparison in the message above. */
    private static Duration finishesAt(double ceiling) {
        BorderSettings at = new BorderSettings(
                HungerGamesSettings.DEFAULTS.borderInitialSize(),
                HungerGamesSettings.DEFAULTS.borderFloor(),
                ceiling,
                settings().phases());
        Duration latest = Duration.ZERO;
        for (int phase = 0; phase < at.phases().size(); phase++) {
            Duration ends = at.phases().get(phase).trigger().time().orElseThrow()
                    .plus(BorderMath.effectiveDuration(at, phase));
            if (ends.compareTo(latest) > 0) {
                latest = ends;
            }
        }
        return latest;
    }

    @Test
    @DisplayName("a round short enough to be caught is caught")
    void theCheckIsNotVacuous() {
        // Without this, the two tests above would pass just as happily against a validate() that had
        // stopped looking at the game duration at all — which is the way this check would actually
        // break, since nothing else here would notice.
        List<BorderConflict> conflicts = BorderMath.validate(settings(), Optional.of(Duration.ofMinutes(30)));

        assertThat(conflicts)
                .as("a 30-minute round cannot carry phases triggering at 90 and 144 minutes")
                .isNotEmpty();
    }
}
