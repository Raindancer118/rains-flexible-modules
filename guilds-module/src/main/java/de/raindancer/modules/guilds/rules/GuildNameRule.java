package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.GuildOutcome;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whether a name or a tag may be used. Names are words; tags are what sits in front of every chat line, so they
 * are letters and digits only — no spaces, no markup, nothing a client draws oddly.
 *
 * <p>Banned words are matched against a folded form (lower case, accents and look-alike digits taken out), so
 * "B4dW0rd" and "bädword" are the same word as "badword".
 */
public final class GuildNameRule implements IGuildsRule {

    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} '_-]*");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]+");

    private final int shortestName;
    private final int longestName;
    private final int shortestTag;
    private final int longestTag;
    private final List<String> banned;

    public GuildNameRule(int shortestName, int longestName, int shortestTag, int longestTag, List<String> banned) {
        this.shortestName = Math.max(1, shortestName);
        this.longestName = Math.max(this.shortestName, longestName);
        this.shortestTag = Math.max(1, shortestTag);
        this.longestTag = Math.max(this.shortestTag, longestTag);
        this.banned = banned == null ? List.of() : banned.stream()
                .map(GuildNameRule::folded).filter(word -> !word.isBlank()).toList();
    }

    @Override
    public String describe() {
        return "a guild's name is " + shortestName + "–" + longestName + " characters and its tag "
                + shortestTag + "–" + longestTag + " letters or digits, with no banned word in either";
    }

    /** What is wrong with the name, or empty when it may be used. */
    public Optional<GuildOutcome> name(String typed) {
        String name = tidy(typed);
        if (name.length() < shortestName) {
            return Optional.of(GuildOutcome.NAME_TOO_SHORT);
        }
        if (name.length() > longestName) {
            return Optional.of(GuildOutcome.NAME_TOO_LONG);
        }
        if (!NAME.matcher(name).matches()) {
            return Optional.of(GuildOutcome.NAME_CHARACTERS);
        }
        return containsBanned(name) ? Optional.of(GuildOutcome.NAME_BANNED) : Optional.empty();
    }

    /** A rank's name: like a guild's, but a single short word such as "Elder" is fine. */
    public Optional<GuildOutcome> rankName(String typed) {
        String name = tidy(typed);
        if (name.isEmpty()) {
            return Optional.of(GuildOutcome.NAME_TOO_SHORT);
        }
        if (name.length() > longestName) {
            return Optional.of(GuildOutcome.NAME_TOO_LONG);
        }
        if (!NAME.matcher(name).matches()) {
            return Optional.of(GuildOutcome.NAME_CHARACTERS);
        }
        return containsBanned(name) ? Optional.of(GuildOutcome.NAME_BANNED) : Optional.empty();
    }

    public Optional<GuildOutcome> tag(String typed) {
        String tag = typed == null ? "" : typed.strip();
        if (tag.length() < shortestTag) {
            return Optional.of(GuildOutcome.TAG_TOO_SHORT);
        }
        if (tag.length() > longestTag) {
            return Optional.of(GuildOutcome.TAG_TOO_LONG);
        }
        if (!TAG.matcher(tag).matches()) {
            return Optional.of(GuildOutcome.TAG_CHARACTERS);
        }
        return containsBanned(tag) ? Optional.of(GuildOutcome.TAG_BANNED) : Optional.empty();
    }

    /** Spaces at the ends taken off, runs of spaces made one. */
    public static String tidy(String typed) {
        return typed == null ? "" : typed.strip().replaceAll("\\s+", " ");
    }

    private boolean containsBanned(String text) {
        String folded = folded(text);
        return banned.stream().anyMatch(folded::contains);
    }

    static String folded(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT)
                .replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a')
                .replace('5', 's').replace('7', 't').replace('@', 'a').replace('$', 's')
                .replaceAll("[^a-z]", "");
    }
}
