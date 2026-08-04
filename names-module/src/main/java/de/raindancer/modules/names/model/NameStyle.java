package de.raindancer.modules.names.model;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How a name is to be painted: a list of colour stops, and the decorations that go with them.
 *
 * <h2>Why a list of colours and not one</h2>
 * One stop is a solid colour, two are a gradient, and more are a multi-stop gradient. Making the
 * single colour the special case of the list rather than the other way round means the gradient is
 * not a second code path bolted on later — {@code util.Naming} interpolates over whatever it is
 * given, and a one-stop "gradient" is simply that colour everywhere.
 *
 * <h2>Values are immutable</h2>
 * A style is read off an item, combined with others and written back, often several times inside one
 * crafting preview. Every method here returns a new style rather than editing this one, so a style
 * that has been put on an item cannot change afterwards under someone else's hand.
 *
 * <h2>Why the encoding lives here and not in the store</h2>
 * The two strings below are what a name tag carries in its persistent data, and they are read on
 * servers running a different build of this code. Keeping the format with the value means the format
 * can be tested without a server — and it is, both directions, because an item written by yesterday's
 * jar has to stay readable by today's.
 */
public record NameStyle(List<TextColor> colours, Set<TextDecoration> decorations) {

    /** No colour, no decoration: what an untouched name tag carries. */
    public static final NameStyle NONE = new NameStyle(List.of(), Set.of());

    /** The separator inside the two persistent-data strings. Never appears in a hex code or a name. */
    private static final String SEPARATOR = ",";

    public NameStyle {
        colours = List.copyOf(colours);
        // An EnumSet keeps declaration order, so encoding a style twice produces the same string and
        // two styles built by different routes compare equal. A HashSet would make both accidental.
        decorations = decorations.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(decorations));
    }

    /** Nothing to paint. A tag carrying this is an ordinary name tag. */
    public boolean isEmpty() {
        return colours.isEmpty() && decorations.isEmpty();
    }

    /** Replaces the colour outright. A dye sets the colour rather than adding a stop to it. */
    public NameStyle withColour(TextColor colour) {
        return new NameStyle(List.of(colour), decorations);
    }

    /**
     * Adds the decoration if it is missing, removes it if it is there.
     *
     * <p>Toggling rather than adding is what makes a mistake cheap: an iron ingot put in by accident
     * is undone by another iron ingot, and the player never has to know there is a way to start over.
     */
    public NameStyle toggle(TextDecoration decoration) {
        Set<TextDecoration> next = decorations.isEmpty()
                ? EnumSet.noneOf(TextDecoration.class)
                : EnumSet.copyOf(decorations);
        if (!next.remove(decoration)) {
            next.add(decoration);
        }
        return new NameStyle(colours, next);
    }

    /**
     * Lays several styles end to end: the colours become the stops of one gradient, in the order
     * given, and every decoration any of them carries is kept.
     *
     * <p>The order is the caller's, and the caller reads the crafting grid left to right — that is
     * the whole reason a red tag on the left and a blue one on the right run red to blue rather than
     * the other way round.
     */
    public static NameStyle merge(List<NameStyle> stops) {
        List<TextColor> colours = new ArrayList<>();
        Set<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
        for (NameStyle stop : stops) {
            colours.addAll(stop.colours());
            decorations.addAll(stop.decorations());
        }
        return new NameStyle(colours, decorations);
    }

    // ------------------------------------------------------------------ persistence

    /**
     * The colours as {@code #rrggbb,#rrggbb}.
     *
     * <p>Hex rather than a colour name even for the sixteen named colours: a name would have to
     * survive Adventure renaming a constant, and a server owner who configures a hex colour would
     * otherwise need a second encoding. One form for everything, readable in an NBT dump.
     */
    public String encodeColours() {
        return colours.stream().map(TextColor::asHexString)
                .reduce((a, b) -> a + SEPARATOR + b).orElse("");
    }

    public String encodeDecorations() {
        return decorations.stream()
                .map(decoration -> decoration.name().toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + SEPARATOR + b).orElse("");
    }

    /**
     * The inverse, forgiving of anything it does not recognise.
     *
     * <p>These strings come off an item that may have been written by an older build, edited by an
     * operator with an NBT tool, or brought in from another server. An unreadable entry is dropped
     * and the rest of the style is kept, because the alternative — refusing the whole item — turns a
     * cosmetic mistake into a name tag that cannot be used at all.
     */
    public static NameStyle decode(String colours, String decorations) {
        List<TextColor> stops = new ArrayList<>();
        for (String part : split(colours)) {
            TextColor colour = TextColor.fromHexString(part);
            if (colour != null) {
                stops.add(colour);
            }
        }
        Set<TextDecoration> found = EnumSet.noneOf(TextDecoration.class);
        for (String part : split(decorations)) {
            TextDecoration decoration = TextDecoration.NAMES.value(part.toLowerCase(Locale.ROOT));
            if (decoration != null) {
                found.add(decoration);
            }
        }
        return new NameStyle(stops, found);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(SEPARATOR)).map(String::trim)
                .filter(part -> !part.isEmpty()).toList();
    }
}
