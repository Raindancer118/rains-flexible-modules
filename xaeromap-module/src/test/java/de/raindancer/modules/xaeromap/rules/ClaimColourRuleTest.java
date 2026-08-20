package de.raindancer.modules.xaeromap.rules;

import de.raindancer.modules.xaeromap.Facts;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That claims are told apart by colour, and that a colour does not change between refreshes.
 *
 * <p>A map whose colours move is a map somebody stops trusting — the claim they learned as the blue one
 * is red after a restart, so the colour stops carrying information at all.
 */
class ClaimColourRuleTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID NEIGHBOUR = UUID.randomUUID();

    private final ClaimColourRule rule =
            new ClaimColourRule(NamedTextColor.GREEN, NamedTextColor.AQUA);

    @Test
    @DisplayName("your own, one shared with you, and a stranger's are three different colours")
    void thethreeCasesAreDistinct() {
        ClaimFacts mine = Facts.claim("Mine", VIEWER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimFacts shared = Facts.claim("Shared", NEIGHBOUR, Facts.OVERWORLD, 0L, Set.of(VIEWER),
                Facts.chunk(1, 0));
        ClaimFacts theirs = Facts.claim("Theirs", NEIGHBOUR, Facts.OVERWORLD, Facts.chunk(2, 0));

        int own = rule.colourFor(VIEWER, mine);
        int trusted = rule.colourFor(VIEWER, shared);
        int stranger = rule.colourFor(VIEWER, theirs);

        assertThat(own).isEqualTo(NamedTextColor.GREEN.value());
        assertThat(trusted).isEqualTo(NamedTextColor.AQUA.value());
        assertThat(stranger).isNotEqualTo(own).isNotEqualTo(trusted);
    }

    @Test
    @DisplayName("the same claim is the same colour on somebody else's map, from their point of view")
    void colourIsPerViewer() {
        ClaimFacts mine = Facts.claim("Mine", VIEWER, Facts.OVERWORLD, Facts.chunk(0, 0));

        assertThat(rule.colourFor(VIEWER, mine)).isEqualTo(NamedTextColor.GREEN.value());
        assertThat(rule.colourFor(NEIGHBOUR, mine))
                .as("their own colour is for their own claims — everybody's map would otherwise be "
                        + "entirely green")
                .isNotEqualTo(NamedTextColor.GREEN.value());
    }

    @Test
    @DisplayName("a stranger's colour is the same every time it is asked for")
    void strangerColoursAreStable() {
        UUID owner = UUID.randomUUID();

        assertThat(ClaimColourRule.hueOf(owner)).isEqualTo(ClaimColourRule.hueOf(owner));
    }

    @Test
    @DisplayName("neighbours who joined seconds apart still get colours apart")
    void similarUuidsDoNotShareAHue() {
        // Sequential uuids are the pathological input: two players whose ids differ in a handful of
        // bits. Taken straight modulo 360 they land on neighbouring hues and the map reads as one blob.
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            seen.add(ClaimColourRule.hueOf(new UUID(0x1122334455667788L, i)));
        }

        assertThat(seen)
                .as("64 near-identical uuids collapsed onto %d colour(s)", seen.size())
                .hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("every colour is a real 24-bit colour, and none of them is black or white")
    void coloursAreReadable() {
        for (int i = 0; i < 500; i++) {
            int colour = ClaimColourRule.hueOf(UUID.randomUUID());

            assertThat(colour).isBetween(0, 0xFFFFFF);
            assertThat(colour)
                    .as("black is invisible against a cave and white against snow — the point of "
                            + "fixing saturation and brightness is that neither can happen")
                    .isNotZero()
                    .isNotEqualTo(0xFFFFFF);
        }
    }

    @Test
    @DisplayName("the colour arithmetic agrees with the one in the standard library")
    void itMatchesAKnownImplementation() {
        // The hand-written HSB conversion exists to keep java.desktop out of a headless server, not to
        // be different from it. Checked against java.awt here, where the module cannot see it.
        for (int degrees = 0; degrees < 360; degrees += 7) {
            for (float saturation : new float[] { 0f, 0.35f, 0.65f, 1f }) {
                for (float brightness : new float[] { 0.2f, 0.95f, 1f }) {
                    float hue = degrees / 360f;

                    assertThat(ClaimColourRule.rgb(hue, saturation, brightness))
                            .as("hue %d, saturation %s, brightness %s", degrees, saturation, brightness)
                            .isEqualTo(java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF);
                }
            }
        }
    }

    @Test
    @DisplayName("no colours configured still draws a map")
    void thereAreDefaults() {
        ClaimColourRule fallback = new ClaimColourRule(null, null);
        ClaimFacts mine = Facts.claim("Mine", VIEWER, Facts.OVERWORLD, Facts.chunk(0, 0));

        assertThat(fallback.colourFor(VIEWER, mine)).isEqualTo(NamedTextColor.GREEN.value());
        assertThat(ClaimColourRule.hueOf(null)).isEqualTo(NamedTextColor.WHITE.value());
    }
}
