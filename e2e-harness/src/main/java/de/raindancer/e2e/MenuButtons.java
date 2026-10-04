package de.raindancer.e2e;

import java.util.Locale;

/**
 * What counts as "the same button" — for the crawler deciding what is left to click, and for the
 * run-time coverage check deciding what was never clicked.
 *
 * <ul>
 *   <li>A list's entries (a Core {@code PaginatedMenu}'s rows) are one button: the same handler over
 *       different data. Every entry of the first list page crawled is still clicked.</li>
 *   <li>Any other button is its page, its slot and its name without what changes with state — the
 *       part after a colon, anything in brackets, numbers: "Goal: [Enter the End]" and
 *       "Goal: [Mine a diamond]" are one button, "You are racing" and "You are not racing" two.</li>
 * </ul>
 */
public final class MenuButtons {

    private MenuButtons() {
    }

    public static String key(String holder, int slot, String name, boolean entry) {
        return entry ? holder + " · an entry" : holder + " · " + slot + " " + normalize(name);
    }

    static String normalize(String name) {
        String plain = name == null ? "" : name;
        int colon = plain.indexOf(':');
        if (colon > 0) {
            plain = plain.substring(0, colon);
        }
        return plain.replaceAll("\\[[^]]*]", "").replaceAll("[0-9]", "").replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }
}
