package de.raindancer.modules.cosmetics.rules;

import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads what somebody typed after {@code /cosmetics name set}: colours in the order typed — a palette
 * label, a chat colour or a hex code — and decorations anywhere among them.
 *
 * <p>Only reads; whether they may wear it is {@link NameStyleRule}'s question.
 */
public final class TypedStyleRule implements ICosmeticsRule {

    public Parsed<NameStyle> read(List<String> words, Catalogue catalogue) {
        if (words.isEmpty()) {
            return Parsed.no("Name at least one colour — a palette colour, a chat colour or a #hex code.");
        }
        // The palette first: on a server whose "red" is the dye's red, typing red means that red.
        de.raindancer.core.ui.text.TypedStyles.Read read = de.raindancer.core.ui.text.TypedStyles.read(words,
                word -> catalogue.colourNamed(word).map(PaletteColour::colour).orElse(null));
        return read.style().map(Parsed::ok).orElseGet(() -> Parsed.no(read.problem()));
    }

    /** {@code bold}, and the two spellings people actually use for the long ones. */
    public static TextDecoration decorationOf(String word) {
        return de.raindancer.core.ui.text.TypedStyles.decorationOf(word);
    }

    @Override
    public String describe() {
        return "reading a typed name style";
    }
}
