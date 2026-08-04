package de.raindancer.modules.names.util;

import de.raindancer.modules.names.model.NameStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link NameStyle} into the component an item or a mob actually wears.
 *
 * <h2>Why this is not Core's</h2>
 * Core has a gradient — {@code Brand} paints the server's tag with MiniMessage's {@code <gradient>},
 * which takes two stops and applies them to a whole string. This is a different thing: an arbitrary
 * number of stops, interpolated per character, over text that has to keep working as an item name. The
 * day a second plugin wants that, it belongs in Core; one plugin wanting it is what {@code util} is for.
 *
 * <h2>Every decoration is stated, never left to inherit</h2>
 * Minecraft renders a custom item name in italics unless something says otherwise, and an unspecified
 * decoration on a component inherits from its parent — which, for an item name, is that vanilla italic.
 * A style that says nothing about italic would therefore silently mean "italic", and the stick that is
 * supposed to <em>turn italics on</em> would appear to do nothing. So all five decorations are written
 * out explicitly, as {@code TRUE} or {@code FALSE}, on every name this class produces.
 *
 * <h2>A gradient is per character, and the text is flattened first</h2>
 * There is no way to colour part of a character, so a gradient is a colour per character with the stops
 * spread evenly across the whole string. That means rebuilding the name from its plain text, which
 * throws away any styling it already had — acceptable, because a style with colours in it is a statement
 * about the whole name. A style with <em>no</em> colours is not, and that case is handled without
 * flattening, so bolding an existing gradient keeps the gradient.
 */
public final class Naming {

    /** What the lore preview paints when the tag has no name of its own to show. */
    public static final String SAMPLE = "Abcdefg";

    private Naming() {
    }

    /**
     * Repaints {@code base} in {@code style}.
     *
     * @param base  the name as it is now — a custom name, or the item's own translated name
     * @param style what to paint it in; {@link NameStyle#NONE} returns {@code base} unchanged
     */
    public static Component apply(Component base, NameStyle style) {
        if (style.isEmpty()) {
            return base;
        }
        if (style.colours().isEmpty()) {
            // Decorations only. Keeping the original component rather than flattening it is what lets a
            // bold-only tag be applied to a name that is already a gradient without erasing it.
            return base.decorations(states(style));
        }
        return styled(PlainTextComponentSerializer.plainText().serialize(base), style);
    }

    /** The same, from plain text — used for the lore preview and for anything with no name yet. */
    public static Component styled(String text, NameStyle style) {
        List<TextColor> stops = style.colours();
        TextComponent.Builder builder = Component.text().decorations(states(style));

        if (stops.isEmpty()) {
            return builder.append(Component.text(text)).build();
        }
        if (stops.size() == 1) {
            // Not a special case for the maths — a one-stop gradient is already this colour everywhere
            // — but for the result: one component instead of one per character.
            return builder.append(Component.text(text, stops.getFirst())).build();
        }
        int length = text.length();
        for (int index = 0; index < length; index++) {
            builder.append(Component.text(text.charAt(index)).color(colourAt(stops, index, length)));
        }
        return builder.build();
    }

    /**
     * The colour of character {@code index} of {@code length} across {@code stops}.
     *
     * <p>The first character is the first stop and the last character is the last stop, so a two-letter
     * name shows both ends of a gradient rather than starting it and stopping halfway. A one-character
     * name has nowhere to travel and takes the first stop.
     */
    public static TextColor colourAt(List<TextColor> stops, int index, int length) {
        if (length <= 1 || stops.size() == 1) {
            return stops.getFirst();
        }
        double position = (double) index / (length - 1) * (stops.size() - 1);
        int lower = (int) Math.floor(position);
        if (lower >= stops.size() - 1) {
            return stops.getLast();
        }
        return TextColor.lerp((float) (position - lower), stops.get(lower), stops.get(lower + 1));
    }

    /** Every decoration, said out loud. See the class note on why none of them may be left unset. */
    private static Map<TextDecoration, TextDecoration.State> states(NameStyle style) {
        Map<TextDecoration, TextDecoration.State> states = new LinkedHashMap<>();
        for (TextDecoration decoration : TextDecoration.values()) {
            states.put(decoration, style.decorations().contains(decoration)
                    ? TextDecoration.State.TRUE
                    : TextDecoration.State.FALSE);
        }
        return states;
    }
}
