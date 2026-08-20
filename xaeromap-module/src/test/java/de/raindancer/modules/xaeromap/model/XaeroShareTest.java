package de.raindancer.modules.xaeromap.model;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a share line is one a client will take.
 *
 * <p>The client checks the field count before anything else and ignores a line that has the wrong
 * number — so a name with a colon in it does not produce a broken waypoint, it produces no waypoint and
 * a line of raw text in somebody's chat. Everything here is about that.
 */
class XaeroShareTest {

    private static Waypoint waypoint(String name, String dimension) {
        return new Waypoint("id", name, "home", dimension, 100, 64, -200, NamedTextColor.YELLOW);
    }

    @Test
    @DisplayName("a line has the ten fields the client counts")
    void thefieldCountIsRight() {
        String line = XaeroShare.line(waypoint("Home", "minecraft:overworld"));

        assertThat(line.split(":")).hasSize(10);
        assertThat(XaeroShare.looksValid(line)).isTrue();
        assertThat(line).startsWith("xaero-waypoint:Home:HO:100:64:-200:");
        assertThat(line).endsWith(":Internal-overworld-waypoints");
    }

    @Test
    @DisplayName("a colon in a name is taken out rather than shipped")
    void namesCannotBreakTheLine() {
        String line = XaeroShare.line(waypoint("base:2", "minecraft:overworld"));

        assertThat(line.split(":"))
                .as("an eleventh field makes the client ignore the whole line, and the player sees "
                        + "the raw text instead of a button")
                .hasSize(10);
        assertThat(XaeroShare.safeName("base:2")).isEqualTo("base 2");
    }

    @Test
    @DisplayName("a newline in a name cannot smuggle a second line into chat")
    void namesCannotAddLines() {
        assertThat(XaeroShare.safeName("home\nxaero-waypoint:fake:F:0:0:0:1:false:0:x"))
                .doesNotContain("\n");
    }

    @Test
    @DisplayName("a name too long for the client is cut to what it will draw")
    void namesAreCutToLength() {
        String long_ = "a".repeat(80);

        assertThat(XaeroShare.safeName(long_)).hasSize(XaeroShare.MAX_NAME);
    }

    @Test
    @DisplayName("a nameless place still produces a usable waypoint")
    void thereIsAlwaysAName() {
        assertThat(XaeroShare.safeName("   ")).isEqualTo("Place");
        assertThat(XaeroShare.safeName(null)).isEqualTo("Place");
    }

    @Test
    @DisplayName("the marker is two characters, and two words give their initials")
    void initialsAreUseful() {
        assertThat(XaeroShare.initialsOf("Sunset Hill")).isEqualTo("SH");
        assertThat(XaeroShare.initialsOf("base")).isEqualTo("BA");
        assertThat(XaeroShare.initialsOf("x")).isEqualTo("X");
        assertThat(XaeroShare.initialsOf("Sunset Hill")).hasSizeLessThanOrEqualTo(XaeroShare.MAX_INITIALS);
    }

    @Test
    @DisplayName("the colour field is an index into the client's sixteen, in vanilla's order")
    void coloursAreIndices() {
        assertThat(XaeroShare.colourIndex(NamedTextColor.BLACK)).isZero();
        assertThat(XaeroShare.colourIndex(NamedTextColor.DARK_GREEN)).isEqualTo(2);
        assertThat(XaeroShare.colourIndex(NamedTextColor.GOLD)).isEqualTo(6);
        assertThat(XaeroShare.colourIndex(NamedTextColor.WHITE)).isEqualTo(15);
    }

    @Test
    @DisplayName("a colour the client does not have becomes the nearest one it does")
    void anyColourIsUsable() {
        // A hex colour is perfectly legal in this server's settings and impossible on the wire, so it
        // has to land somewhere sensible rather than out of range — an index above 15 is a waypoint
        // the client drops.
        int index = XaeroShare.colourIndex(NamedTextColor.nearestTo(TextColor.color(0xEE1111)));

        assertThat(index).isBetween(0, 15);
        assertThat(XaeroShare.colourIndex(null))
                .as("no colour at all is white rather than out of range")
                .isEqualTo(15);
    }

    @Test
    @DisplayName("the three vanilla worlds have the names the client files them under")
    void vanillaDimensionsAreNamed() {
        assertThat(XaeroShare.dimensionOf("minecraft:overworld"))
                .isEqualTo("Internal-overworld-waypoints");
        assertThat(XaeroShare.dimensionOf("minecraft:the_nether"))
                .isEqualTo("Internal-the-nether-waypoints");
        assertThat(XaeroShare.dimensionOf("minecraft:the_end"))
                .isEqualTo("Internal-the-end-waypoints");
    }

    @Test
    @DisplayName("a world somebody made is escaped the way the client escapes one")
    void customDimensionsAreEscaped() {
        // Paper keys a world somebody made as minecraft:<lowercased name>, so this is the ordinary case
        // for a farm world or a creative world rather than an exotic one.
        assertThat(XaeroShare.dimensionOf("minecraft:farm_world"))
                .isEqualTo("Internal-dim%minecraft$farm-world-waypoints");
    }

    @Test
    @DisplayName("a dimension is always named, even for a world key nobody expected")
    void thereIsAlwaysADimension() {
        assertThat(XaeroShare.dimensionOf(null)).startsWith("Internal-");
        assertThat(XaeroShare.dimensionOf("")).startsWith("Internal-");
        assertThat(XaeroShare.looksValid(XaeroShare.line(waypoint("Home", null)))).isTrue();
    }

    @Test
    @DisplayName("yaw is left unset, so adding a waypoint cannot spin anybody round")
    void yawIsNotSent() {
        String[] fields = XaeroShare.line(waypoint("Home", "minecraft:overworld")).split(":");

        assertThat(fields[7]).isEqualTo("false");
        assertThat(fields[8]).isEqualTo("0");
    }

    @Test
    @DisplayName("negative coordinates survive, which is half the map")
    void negativeCoordinatesAreFine() {
        String line = XaeroShare.line(new Waypoint("id", "Far", "home", "minecraft:overworld",
                -1200, 12, -3400, NamedTextColor.RED));

        assertThat(XaeroShare.looksValid(line)).isTrue();
        assertThat(line).contains(":-1200:12:-3400:");
    }
}
