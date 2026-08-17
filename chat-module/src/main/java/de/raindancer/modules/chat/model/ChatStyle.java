package de.raindancer.modules.chat.model;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * How one player has chosen to have their own chat messages look — a colour, and which of the four
 * readable decorations are on. Obfuscated is deliberately not one of them: a message nobody can read
 * is not a style choice, it is a prank on whoever it is aimed at.
 *
 * <h2>Why this is not just an Adventure {@link Style}</h2>
 * Because a {@code Style} can hold a hex colour, a font, a shadow colour and more — every one of
 * which is one more thing {@link de.raindancer.modules.chat.store.ChatStyleStore} would have to
 * round-trip through YAML and one more thing the picker GUI would have to draw a page for. Sixteen
 * named colours are the entire chat palette a vanilla client already knows how to show, which is
 * exactly what makes a swatch grid work as a chooser at all.
 *
 * @param color         null means "no colour chosen" — the message renders in whatever colour it
 *                      would have without this feature at all, not black
 */
public record ChatStyle(NamedTextColor color, boolean bold, boolean italic, boolean underlined,
                        boolean strikethrough) {

    /** Nobody has chosen anything: no colour, no decoration — messages render exactly as before. */
    public static final ChatStyle DEFAULT = new ChatStyle(null, false, false, false, false);

    /** Whether this is worth writing to disk at all — {@link #DEFAULT} is the same as never having asked. */
    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** The same style, with a different colour — null clears it back to "unchosen". */
    public ChatStyle withColor(NamedTextColor newColor) {
        return new ChatStyle(newColor, bold, italic, underlined, strikethrough);
    }

    /** The same style, with one decoration flipped. */
    public ChatStyle withDecoration(TextDecoration decoration, boolean value) {
        return switch (decoration) {
            case BOLD -> new ChatStyle(color, value, italic, underlined, strikethrough);
            case ITALIC -> new ChatStyle(color, bold, value, underlined, strikethrough);
            case UNDERLINED -> new ChatStyle(color, bold, italic, value, strikethrough);
            case STRIKETHROUGH -> new ChatStyle(color, bold, italic, underlined, value);
            default -> this;   // obfuscated is not offered; asking for it changes nothing
        };
    }

    public boolean has(TextDecoration decoration) {
        return switch (decoration) {
            case BOLD -> bold;
            case ITALIC -> italic;
            case UNDERLINED -> underlined;
            case STRIKETHROUGH -> strikethrough;
            default -> false;
        };
    }

    /**
     * As an Adventure {@link Style}, to apply to a rendered message.
     *
     * <p>Only ever sets a decoration to {@code TRUE} — never to {@code FALSE} or leaves it unset —
     * so that applying this to a component whose children already carry their own explicit style
     * (a highlighted @-mention, a linkified URL) never strips what they set for themselves. Adventure
     * resolves an unset decoration from the parent; a child's own {@code TRUE} or {@code FALSE} always
     * wins over whatever the parent says.
     */
    public Style asStyle() {
        Style.Builder built = Style.style();
        if (color != null) {
            built.color(color);
        }
        if (bold) {
            built.decorate(TextDecoration.BOLD);
        }
        if (italic) {
            built.decorate(TextDecoration.ITALIC);
        }
        if (underlined) {
            built.decorate(TextDecoration.UNDERLINED);
        }
        if (strikethrough) {
            built.decorate(TextDecoration.STRIKETHROUGH);
        }
        return built.build();
    }
}
