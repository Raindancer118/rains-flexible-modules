package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.service.ArenaBuildService;
import de.raindancer.modules.hungergames.visual.Schematics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the arena's schematics can actually be read out of the jar.
 *
 * <h2>The bug this was written for, found by running /init on a live server</h2>
 * {@code Schematics} asked for {@code getResourceAsStream("schem/tube.schem")}. A relative resource name is
 * resolved against the <em>asking class's own package</em>, and {@code Schematics} lives in
 * {@code …hungergames.visual} — so it looked for {@code de/raindancer/modules/hungergames/visual/schem/}.
 * The files are packaged at {@code de/raindancer/modules/hungergames/schem/}, one level up.
 *
 * <p>Nothing about compiling or packaging notices. {@code BundleJarTest} confirmed the schematics were in the
 * jar, and they were — at the path nothing was looking at. {@code /init} then reported "there is no schematic
 * called 'tube.schem' in this module, and none on disk either", fell back to a guessed tube depth, and gave
 * up when the middle would not paste: an arena of two platforms and no cornucopia, in front of whoever ran it.
 *
 * <p>So this asks the way the production code asks, for every file the arena is made of. A resource path is
 * a string, and a string that is wrong by one package is invisible until somebody builds an arena.
 */
class TheSchematicsAreReachableTest {

    /** Every schematic the arena builder pastes, by the name it asks for. */
    private static final List<String> NEEDED = List.of(
            ArenaBuildService.MIDDLE, ArenaBuildService.TUBE, ArenaBuildService.PLATFORM);

    @Test
    @DisplayName("every schematic the arena needs is where the code looks for it")
    void theyAreWhereTheyAreAskedFor() {
        for (String name : NEEDED) {
            try (InputStream found = Schematics.bundled(name)) {
                assertThat(found)
                        .as("'%s' is not readable the way Schematics asks for it — /init would paste "
                                + "nothing and report an arena it could not finish", name)
                        .isNotNull();
            } catch (java.io.IOException unreadable) {
                throw new AssertionError("could not close the stream for " + name, unreadable);
            }
        }
    }

    @Test
    @DisplayName("the lookup is not simply answering yes to everything")
    void anUnknownNameIsStillUnknown() {
        try (InputStream nothing = Schematics.bundled("there-is-no-such-file.schem")) {
            assertThat(nothing)
                    .as("a lookup that found everything would make the test above meaningless")
                    .isNull();
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
