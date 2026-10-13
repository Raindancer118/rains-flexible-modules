package de.raindancer.modules.ranks.rules;

import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a tag already looks like, as the {@link NameStyle} the colour editor starts from. A tag is the owner's own markup,
 * so this reads it back as closely as a style can say it: one colour stays one, a gradient becomes its first and last
 * colour, and the decorations are the ones on the first letter.
 */
public final class TagStyleRule implements IRanksRule {

    public NameStyle styleOf(String tag) {
        if (tag == null || tag.isBlank()) {
            return NameStyle.NONE;
        }
        Component parsed;
        try {
            parsed = Text.styleOnly().deserialize(tag);
        } catch (RuntimeException broken) {
            return NameStyle.NONE;
        }
        List<TextColor> colours = new ArrayList<>();
        Set<TextDecoration> firstDecorations = new java.util.HashSet<>();
        boolean[] first = {true};
        walk(parsed, Style.empty(), (letter, style) -> {
            if (letter == ' ') {
                return;
            }
            if (style.color() != null) {
                colours.add(style.color());
            }
            if (first[0]) {
                first[0] = false;
                for (TextDecoration decoration : TextDecoration.values()) {
                    if (style.decoration(decoration) == TextDecoration.State.TRUE) {
                        firstDecorations.add(decoration);
                    }
                }
            }
        });
        List<TextColor> stops = new ArrayList<>();
        if (!colours.isEmpty()) {
            stops.add(colours.getFirst());
            if (!colours.getLast().equals(colours.getFirst())) {
                stops.add(colours.getLast());
            }
        }
        Set<TextDecoration> decorations = firstDecorations.isEmpty() ? Set.of() : EnumSet.copyOf(firstDecorations);
        return new NameStyle(stops, decorations);
    }

    /** What the tag says, without its markup. */
    public String lettersOf(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        try {
            return Text.plain(Text.styleOnly().deserialize(tag));
        } catch (RuntimeException broken) {
            return "";
        }
    }

    private static void walk(Component component, Style inherited, java.util.function.BiConsumer<Character, Style> each) {
        Style own = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            for (char letter : text.content().toCharArray()) {
                each.accept(letter, own);
            }
        }
        for (Component child : component.children()) {
            walk(child, own, each);
        }
    }

    @Override
    public String describe() {
        return "what a rank's tag looks like, as a style the colour editor can start from";
    }
}
