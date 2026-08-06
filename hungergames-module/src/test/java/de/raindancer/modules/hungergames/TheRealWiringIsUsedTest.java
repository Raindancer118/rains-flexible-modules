package de.raindancer.modules.hungergames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the module actually uses {@link HungerGamesWiring}, and not the placeholders it grew up with.
 *
 * <h2>The failure this was written for, found on a live server</h2>
 * {@code HungerGamesWiring} — nine hundred lines building the arena service, the launch sequence, the
 * countdown, six loot tables, thirty-nine cues, the cornucopia's protected area and every listener — was
 * complete, tested, and <b>never called</b>. {@code HungerGamesModule.enable} still ran an earlier private
 * {@code wire(...)} that had been written while those services did not exist yet: three
 * {@code notYetAvailable()} stages, a border target that refuses to move, and a screens opener that tells
 * whoever asked that nothing is wired up.
 *
 * <p>The plugin started perfectly. The banner printed, the permissions registered, the version was right, and
 * the log had no errors in it — because nothing had failed. The arena simply could not be built, the loot
 * tables did not exist, no listener was registered, and every cue was silent. The only clue was an absence:
 * the line saying how many cues had been defined was not there, and an absent line is not something anybody
 * greps for.
 *
 * <p>Two honest placeholders, written on purpose and each carrying a javadoc explaining why it refuses rather
 * than pretends, outlived the reason they existed. That is the failure mode of a placeholder that behaves
 * well: it does not break anything, so nothing tells you it is still in the way.
 *
 * <h2>Why this is a source scan</h2>
 * Enabling the module needs a server. What can be checked without one is whether the code that would do the
 * enabling names the real wiring — and whether the placeholders it replaced are still lying around waiting
 * to be used again. Cheap, and it fails in the build rather than in a boot log nobody reads to the end of.
 */
class TheRealWiringIsUsedTest {

    private static final Path MODULE =
            Path.of("src/main/java/de/raindancer/modules/hungergames/HungerGamesModule.java");

    private static String moduleSource() {
        try {
            return Files.readString(MODULE);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + MODULE, unreadable);
        }
    }

    /**
     * The module's source with its comments removed.
     *
     * <p>Because the placeholders are named in prose — the javadoc on {@code wire} explains what used to be
     * there and what it cost, which is exactly the note somebody needs before putting one back. A plain
     * substring search read that explanation as a violation of the rule it explains.
     */
    private static String moduleCode() {
        return moduleSource()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(line -> {
                    int slashes = line.indexOf("//");
                    return slashes < 0 ? line : line.substring(0, slashes);
                })
                .reduce("", (all, line) -> all + line + "\n");
    }

    @Test
    @DisplayName("enabling the module builds the real wiring")
    void theWiringIsReached() {
        assertThat(moduleSource())
                .as("HungerGamesWiring builds the arena, the launch sequence, the countdown, the loot "
                        + "tables, the cues, the cornucopia and every listener. Not naming it here is a "
                        + "plugin that starts cleanly and cannot run a tournament")
                .contains("HungerGamesWiring");
    }

    @Test
    @DisplayName("the wiring is started, not merely constructed")
    void theWiringIsStarted() {
        // Constructing it builds the services; start() is what registers the listeners, defines the cues and
        // the loot tables, and hands the services to the commands. Half of it is worse than none, because
        // the commands would answer and do nothing.
        assertThat(moduleSource())
                .as("HungerGamesWiring.start() is what subscribes the listeners and defines the cues")
                .contains(".start()");
    }

    @Test
    @DisplayName("nothing is left wired to a stage that never runs")
    void noStageIsAPlaceholder() {
        assertThat(moduleCode())
                .as("GameControlService.notYetAvailable() refuses honestly, which is right while the arena "
                        + "service does not exist and wrong once it does — /init would report that the "
                        + "arena cannot be built while the code to build it sits unused")
                .doesNotContain("notYetAvailable()");
    }

    @Test
    @DisplayName("the border is pointed at a real world border")
    void theBorderIsReal() {
        assertThat(moduleCode())
                .as("NoBorderYet reports the border as unmoved and refuses to move it — correct before an "
                        + "arena existed, and a border that never closes now")
                .doesNotContain("NoBorderYet");
    }

    @Test
    @DisplayName("the screens are pointed at the pages that exist")
    void theScreensAreReal() {
        assertThat(moduleCode())
                .as("NoScreensYet tells whoever asked that the screens are not wired up. All twenty-four "
                        + "exist and HungerGamesScreens opens them")
                .doesNotContain("NoScreensYet");
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(moduleSource())
                .contains("class HungerGamesModule")
                .contains("public void enable(");
    }
}
