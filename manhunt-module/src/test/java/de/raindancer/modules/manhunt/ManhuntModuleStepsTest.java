package de.raindancer.modules.manhunt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * That one concern failing at the start of a hunt cannot cost the others.
 *
 * <h2>The bug this exists for</h2>
 * {@code ManhuntService.onStart} takes exactly one hook, so {@code ManhuntModule} stacks five
 * independent concerns behind it — the run record, the first-hunt achievement, the death counters,
 * the borrowed gamerules, the narrator, and the tracking compass. They ran as five bare statements in
 * one lambda, so the <em>first</em> of them to throw silently abandoned every one after it. The
 * compass handout was last, which is why a live hunt could start correctly in every visible way — the
 * countdown, the boss bar, the clock, all of which happen <em>before</em> the hook fires — and hand
 * out no compasses at all, with nothing in the log pointing anywhere near the compass.
 *
 * <p>Reported as "I tested with more people and did still not get a compass", with
 * {@code tracker-compass-enabled} verified on and an empty inventory, which ruled out every other
 * candidate.
 *
 * <h2>Why {@link Throwable} and not {@link RuntimeException}</h2>
 * The realistic failure here is an API that moved: this module compiles against one Paper build and
 * servers run another, and {@code GameRule} in particular is deprecated-for-removal on 26.2. That
 * arrives as {@link NoSuchMethodError} or {@link NoSuchFieldError} — an {@link Error}, which a
 * {@code RuntimeException} guard would let straight through. {@code ModuleCommands.canUse} already
 * catches {@link Throwable} in this reactor for the same class of reason.
 */
class ManhuntModuleStepsTest {

    @Test
    @DisplayName("every step runs, in order, when none of them throws")
    void allStepsRunInOrder() {
        List<String> ran = new ArrayList<>();

        ManhuntModule.step("first", () -> ran.add("first"), ManhuntModuleStepsTest::ignore);
        ManhuntModule.step("second", () -> ran.add("second"), ManhuntModuleStepsTest::ignore);

        assertThat(ran).containsExactly("first", "second");
    }

    @Test
    @DisplayName("a step that throws does not stop the caller — the next one still runs")
    void aThrowingStepIsContained() {
        List<String> ran = new ArrayList<>();

        assertThatCode(() -> {
            ManhuntModule.step("boom", () -> {
                throw new IllegalStateException("the achievement store is not there");
            }, ManhuntModuleStepsTest::ignore);
            ManhuntModule.step("compass", () -> ran.add("compass"), ManhuntModuleStepsTest::ignore);
        }).doesNotThrowAnyException();

        assertThat(ran).containsExactly("compass");
    }

    @Test
    @DisplayName("an Error is contained too — a moved API is the failure actually worth surviving")
    void anErrorIsContained() {
        List<String> ran = new ArrayList<>();

        assertThatCode(() -> {
            ManhuntModule.step("gamerules", () -> {
                throw new NoSuchMethodError("org.bukkit.GameRule.getName()");
            }, ManhuntModuleStepsTest::ignore);
            ManhuntModule.step("compass", () -> ran.add("compass"), ManhuntModuleStepsTest::ignore);
        }).doesNotThrowAnyException();

        assertThat(ran).containsExactly("compass");
    }

    @Test
    @DisplayName("what threw is reported, named by the step, rather than swallowed")
    void theFailureIsReported() {
        List<String> complaints = new ArrayList<>();
        IllegalStateException thrown = new IllegalStateException("nope");

        ManhuntModule.step("handing out the tracking compasses", () -> {
            throw thrown;
        }, (what, trouble) -> complaints.add(what + ": " + trouble.getMessage()));

        assertThat(complaints).containsExactly("handing out the tracking compasses: nope");
    }

    private static void ignore(String what, Throwable trouble) {
    }
}
