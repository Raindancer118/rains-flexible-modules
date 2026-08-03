package de.raindancer.modules.claims.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A bag of named values, in the shape {@code Messages} actually wants.
 *
 * <h2>Why this exists</h2>
 * Core's {@code Messages} takes its values as {@code Object...} — alternating name, value, name, value.
 * Several services here build a {@code Map<String, String>} instead, because a map is the honest shape
 * for "here are six things the wording might use" and passing twelve loose arguments is how the eleventh
 * ends up in the twelfth's place.
 *
 * <p>Handing the map over directly is the trap: a {@code Map} <em>is</em> an {@code Object}, so it
 * compiles, the varargs array has one element, no name is ever read, and <b>nothing is substituted</b>.
 * The player is shown the raw wording:
 *
 * <pre>
 *   &lt;player&gt; was barred from &lt;claim&gt; by &lt;by&gt;. &lt;reason&gt;
 * </pre>
 *
 * <p>Nothing throws and nothing is logged. It was found by reading a screenshot of somebody's chat.
 * {@code PlaceholdersTest} now fails the build if any service passes a map straight in again.
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * Flattens a map into the alternating array the message API reads.
     *
     * <p>A null value becomes an empty string rather than being dropped or passed on: MiniMessage
     * renders a null argument as the text "null", which reads to the player as a bug and hides which
     * value was actually missing. Dropping the pair instead would leave the placeholder raw, which is
     * the very thing this class is about.
     */
    public static Object[] of(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return new Object[0];
        }
        List<Object> flat = new ArrayList<>(values.size() * 2);
        values.forEach((name, value) -> {
            flat.add(name);
            flat.add(value == null ? "" : value);
        });
        return flat.toArray();
    }
}
