package de.raindancer.modules.warp.rules;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a warp may be called, and how many there may be.
 *
 * <h2>Why {@link #RESERVED} is not tidiness</h2>
 * {@code /warp list} has to mean one of two things, and it means the subcommand — the command reads
 * the first word as one before it reads it as a name. So a warp called {@code list} is a warp
 * nothing can ever reach: it appears in the menu, it can be clicked, and the command for it silently
 * prints a list instead. RainsCore's own {@code /warp} still has that hole; this is why this module
 * does not.
 *
 * <p>The list here is the command's own, imported by it rather than written twice — two copies is
 * one that gets a new subcommand added to it and one that does not.
 */
public final class WarpNameRule implements IWarpRule {

    /**
     * The words {@code /warp} reads as instructions.
     *
     * <p>The command takes its subcommands from this list, so adding one there cannot leave a warp
     * name silently unreachable here.
     */
    public static final List<String> RESERVED = List.of(
            "list", "admin", "config", "settings", "set", "setwarp", "delete", "remove", "delwarp",
            "move", "category", "permission", "access", "icon", "label", "help", "reload");

    /**
     * What a name may be made of.
     *
     * <p>Letters, digits, a dash and an underscore. Everything else is refused rather than escaped,
     * for two separate reasons: a space cannot be typed at the command at all, and a name carrying
     * markup goes on to be drawn in a message, a lore line and a chat row — where it could paint or
     * hide the module's own wording on somebody else's screen.
     */
    private static final Pattern TYPEABLE = Pattern.compile("[A-Za-z0-9_-]+");

    /** What was wrong with a name, or that nothing was. */
    public enum Verdict {

        FINE(null),
        EMPTY("warps.name.empty"),
        TOO_LONG("warps.name.too-long"),
        BAD_CHARACTERS("warps.name.bad-characters"),
        RESERVED("warps.name.reserved");

        private final String messageKey;

        Verdict(String messageKey) {
            this.messageKey = messageKey;
        }

        /** The wording for this refusal, or null when there is nothing to refuse. */
        public String messageKey() {
            return messageKey;
        }

        public boolean isFine() {
            return this == FINE;
        }
    }

    private final int longestName;

    public WarpNameRule(int longestName) {
        this.longestName = longestName;
    }

    /** What, if anything, is wrong with this name. */
    public Verdict check(String name) {
        if (name == null || name.isBlank()) {
            return Verdict.EMPTY;
        }
        String trimmed = name.trim();
        if (trimmed.length() > longestName) {
            return Verdict.TOO_LONG;
        }
        if (!TYPEABLE.matcher(trimmed).matches()) {
            return Verdict.BAD_CHARACTERS;
        }
        // Case-insensitively, because the command matches its subcommands that way: /warp LIST is
        // still the list, so a warp called LIST would still be unreachable.
        if (RESERVED.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return Verdict.RESERVED;
        }
        return Verdict.FINE;
    }

    /**
     * Whether there is room for one more.
     *
     * <p>A ceiling so that a script cannot fill the place store. Reaching it is a refusal with a
     * line saying so, never a quiet nothing — the quiet version is what gets reported as "setting
     * warps has stopped working".
     */
    public boolean isRoomFor(int howManyThereAre, int ceiling) {
        return howManyThereAre < ceiling;
    }

    /** The longest a name may be here, for the message that says so. */
    public int longestName() {
        return longestName;
    }

    @Override
    public String describe() {
        return "what a warp may be called, and how many there may be";
    }
}
