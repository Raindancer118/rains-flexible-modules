package de.raindancer.modules.moderation.rules;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Whether a typed command is one the server watches for.
 *
 * <h2>Why this is a rule and not a string comparison at the listener</h2>
 * Because "does this label match one of these words" is exactly the kind of question a screen would
 * want to ask speculatively — a future admin page listing the watched commands wants to highlight one
 * that is currently being typed on the server without filing anything — and a rule is what stays askable
 * for that.
 *
 * <h2>Why the match is on the first word only</h2>
 * {@code /seed} and {@code /seedbank} are different commands. Matching a substring anywhere in the
 * typed line would flag {@code /msg seed_hunter hello} for containing the letters, which is not the
 * thing this exists to catch.
 *
 * <h2>Why typos of a long watched word count too</h2>
 * Asked for directly: watching for plain {@code /seed} is pointless on a server that has switched the
 * vanilla command off, but somebody reaching for a seed cracker by hand still types something close to
 * its name — {@code /seedcraker}, {@code /seed-cracker}, {@code /sedcracker}. A short word is matched
 * exactly only: allowing typos on {@code seed} would flag {@code feed} or {@code seen}, and turn the
 * queue into noise nobody trusts. A long word — ten letters or more, so {@code seedcracker} qualifies
 * and {@code seed} does not — is matched within a small edit distance instead.
 */
public final class SuspiciousCommandRule implements IModerationRule {

    /** A watched word has to be at least this long before typos of it are matched too. */
    private static final int SHORTEST_WORD_TO_FUZZ = 10;

    /** How many single-character edits still count as a typo, for a word long enough to fuzz. */
    private static final int TYPO_DISTANCE = 2;

    /**
     * The watched command this label matches, if any.
     *
     * @param typedLabel what {@code PlayerCommandPreprocessEvent} reports — {@code "/seed"},
     *                   {@code "/seed confirm"}, with or without the leading slash
     * @param watched    the server's own list, read without the leading slash
     */
    public Optional<String> matched(String typedLabel, List<String> watched) {
        if (typedLabel == null || typedLabel.isBlank() || watched == null || watched.isEmpty()) {
            return Optional.empty();
        }
        String withoutSlash = typedLabel.startsWith("/") ? typedLabel.substring(1) : typedLabel;
        String firstWord = withoutSlash.split("\\s+", 2)[0];
        // A command sent as "plugin:seed" still means /seed — Bukkit accepts both spellings, and a
        // player using the qualified form is not thereby less suspicious.
        int colon = firstWord.indexOf(':');
        String name = (colon >= 0 ? firstWord.substring(colon + 1) : firstWord).toLowerCase(Locale.ROOT);

        for (String candidate : watched) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String word = candidate.trim().toLowerCase(Locale.ROOT);
            if (word.equals(name)) {
                return Optional.of(word);
            }
            if (word.length() >= SHORTEST_WORD_TO_FUZZ && withinTypoDistance(name, word)) {
                return Optional.of(word);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code typed} is a plausible typo of {@code watched}, by Levenshtein distance.
     *
     * <p>Bounded above by the shorter word's length so an empty or single-letter {@code typed} — which
     * is within two edits of almost anything short — cannot match a long watched word by accident: the
     * loop below already skips words shorter than {@link #SHORTEST_WORD_TO_FUZZ}, and this adds the
     * same guard against the other direction, a short {@code typed} against a long {@code watched}.
     */
    private static boolean withinTypoDistance(String typed, String watched) {
        if (typed.isEmpty() || Math.abs(typed.length() - watched.length()) > TYPO_DISTANCE) {
            return false;
        }
        return levenshtein(typed, watched) <= TYPO_DISTANCE;
    }

    /** Classic edit distance: insertions, deletions and substitutions, each costing one. */
    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    @Override
    public String describe() {
        return "whether a typed command matches, or is a plausible typo of, the server's watched list";
    }
}
