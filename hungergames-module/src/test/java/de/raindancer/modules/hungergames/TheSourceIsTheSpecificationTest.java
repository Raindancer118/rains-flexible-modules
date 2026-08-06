package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.ArenaLayout;
import de.raindancer.modules.hungergames.service.ArenaItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour the old plugin had, demanded of the port — one test per thing a line-by-line reading found.
 *
 * <h2>Why these are in one class and named after the source</h2>
 * Because they have nothing in common except where they came from: somebody read the old plugin's code beside
 * the new code and found a difference nobody chose. A port drifts in small, individually defensible steps —
 * a nicer material here, a tidier coordinate there — and the sum is a game that is no longer the one people
 * learned. Each of these was a step of exactly that kind.
 */
class TheSourceIsTheSpecificationTest {

    @Nested
    @DisplayName("the fiendfinder is the spyglass it always was")
    class Fiendfinder {

        @Test
        @DisplayName("it is a spyglass, not a compass")
        void aSpyglass() {
            // Fiendfinder.java:45 — new ItemStack(Material.SPYGLASS, 1) — and :87 refuses to work on any
            // other material at all, so the material was load-bearing rather than decorative.
            //
            // The port made it a COMPASS. Defensible in isolation: a compass points at things. But it is the
            // item people recognise in a hotbar during a fight, and nobody asked for it to change — which is
            // the whole category of drift these tests exist to stop.
            assertThat(source(ArenaItemService.class))
                    .as("the item players learned to recognise was quietly swapped for a different one")
                    .contains("Material.SPYGLASS")
                    .doesNotContain("Material.COMPASS");
        }
    }

    @Nested
    @DisplayName("a tribute has arrived where the source said they had")
    class Arrival {

        @Test
        @DisplayName("the arrival height is a block and a half above the platform, not two and a half")
        void oneAndAHalf() {
            // StartupRunner.java:355 — double targetY = platformPos.getY() + 1.5 — where platformPos is the
            // PLATFORM's own position, which is the block a tribute stands ON: centreY.
            //
            // In the port, ArenaLayout.Stand.y() is the STANDING position, centreY + 1 — already a block
            // higher. Adding the source's 1.5 to it puts the threshold at centreY + 2.5: a whole block above
            // where the source wanted it.
            //
            // What that costs: the sequence seals a platform and places the ring when the tribute is counted
            // as arrived. A threshold a block too high means the levitation has to carry them further before
            // anything happens, and a tribute whose levitation runs out in between is one the ARRIVAL_TIMEOUT
            // has to rescue — an invisible fault that only shows up as a slow, uneven launch.
            ArenaLayout.Stand platform = new ArenaLayout.Stand(8.5, 65.0, 8.5, 90f);

            // The platform's own block is one below the standing position — the same relationship
            // wayUpThrough() encodes for the block that is opened and sealed.
            double platformBlockY = platform.y() - 1;

            assertThat(arrivalHeightIn(platform))
                    .as("the source's threshold is 1.5 above the platform block, not above the feet")
                    .isEqualTo(platformBlockY + 1.5);
        }

        /** What the port's constant actually resolves to for that platform. */
        private double arrivalHeightIn(ArenaLayout.Stand platform) {
            String text = source(de.raindancer.modules.hungergames.service.StartupSequenceService.class);
            int at = text.indexOf("ARRIVAL_MARGIN = ");
            assertThat(at).as("ARRIVAL_MARGIN is gone; this test no longer checks anything").isPositive();
            String written = text.substring(at + "ARRIVAL_MARGIN = ".length(),
                    text.indexOf(';', at)).strip();

            boolean fromTheBlock = text.contains("platform.y() - 1 + ARRIVAL_MARGIN")
                    || text.contains("platformBlockY + ARRIVAL_MARGIN");
            return (platform.y() - (fromTheBlock ? 1 : 0)) + Double.parseDouble(written);
        }
    }

    /** A class's own source text, so a constant can be checked without standing in an arena. */
    private static String source(Class<?> type) {
        Path file = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
