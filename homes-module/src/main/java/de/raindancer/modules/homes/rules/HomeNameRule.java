package de.raindancer.modules.homes.rules;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a home may be called.
 *
 * <h2>Why the old rule is kept to the character</h2>
 * Because it is already on disk. Every home anybody has set went through the old
 * {@code HomeNames.normalise} — lower-cased, trimmed, {@code [a-z0-9_-]}, at most sixteen characters —
 * and the normalised name is the <em>key</em> under {@code players.<uuid>.homes.} in
 * {@code homes.yml}. Tightening the rule would refuse to load names people already have; loosening it
 * would let two names normalise onto one key and quietly merge two homes.
 *
 * <h2>Why an invalid name is refused rather than cleaned up</h2>
 * Stripping {@code "my base!"} down to {@code "mybase"} gives somebody a home under a name they did
 * not type and cannot guess. Told the rule, they type a name that works.
 */
public final class HomeNameRule implements IHomeRule {

    /** What {@code /sethome} with no argument means. */
    public static final String DEFAULT_NAME = "home";

    /** How long a name may be. The old limit, to the character. */
    public static final int LONGEST = 16;

    private static final Pattern ALLOWED = Pattern.compile("[a-z0-9_-]+");

    /**
     * The name as it is stored, or null when it is not a name at all.
     *
     * <p>Null rather than an empty {@link java.util.Optional} because every caller here treats it as
     * "tell them the rule", and the old code did the same — one shape for the two of them to agree on
     * while both exist.
     */
    public String normalise(String typed) {
        if (typed == null) {
            return null;
        }
        String cleaned = typed.trim().toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty() || cleaned.length() > LONGEST || !ALLOWED.matcher(cleaned).matches()) {
            return null;
        }
        return cleaned;
    }

    /**
     * The same, with nothing at all meaning {@link #DEFAULT_NAME}.
     *
     * <p>Only <em>nothing</em>. A name that was given and is invalid stays invalid rather than
     * becoming the default: silently turning a typo into {@code home} would overwrite the home
     * somebody already has there, which is the worst possible reading of a mistake.
     */
    public String orDefault(String typed) {
        return typed == null || typed.isBlank() ? DEFAULT_NAME : normalise(typed);
    }

    /** The rule, for the line that refuses a name. */
    public String describe() {
        return "letters, digits, - and _, up to " + LONGEST + " characters";
    }
}
