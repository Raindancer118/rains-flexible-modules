package de.raindancer.modules.chat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turning a {@code http://} or {@code https://} address in a plain-text chat line into something
 * coloured and clickable, and leaving everything else exactly as typed.
 *
 * <h2>Why this never touches MiniMessage</h2>
 * The text coming in is whatever a player typed, and parsing that as markup is how a message closes
 * a tag it never opened — the same trap Core's own {@code Chat} refuses for every placeholder it is
 * given. Every segment here is built with {@link Component#text}, never deserialized.
 *
 * <h2>What is deliberately simple</h2>
 * A URL is matched up to the next whitespace, trailing punctuation and all — {@code "see
 * https://example.com."} links the closing full stop along with the address. Stripping it back off
 * correctly means knowing which trailing characters are ever legal in a path versus which end a
 * sentence, and getting that wrong quietly breaks a legitimate link. Being slightly generous about
 * where a link ends is the smaller mistake.
 */
public final class Links {

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private Links() {
    }

    /** The line rebuilt with every address styled and clickable; unchanged if there is none. */
    public static Component linkify(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return Component.text(plainText == null ? "" : plainText);
        }
        Matcher matcher = URL.matcher(plainText);
        Component built = Component.empty();
        int last = 0;
        while (matcher.find()) {
            built = built.append(Component.text(plainText.substring(last, matcher.start())));
            built = built.append(styled(matcher.group()));
            last = matcher.end();
        }
        built = built.append(Component.text(plainText.substring(last)));
        return built;
    }

    private static Component styled(String url) {
        try {
            return Component.text(url)
                    .color(NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Open " + url)));
        } catch (RuntimeException notActuallyAUrl) {
            // Matched the pattern but is not something a client can open — plain text beats losing
            // the rest of the line over one malformed address.
            return Component.text(url);
        }
    }
}
