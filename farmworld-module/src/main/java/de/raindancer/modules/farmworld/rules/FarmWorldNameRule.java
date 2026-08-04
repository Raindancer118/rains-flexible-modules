package de.raindancer.modules.farmworld.rules;

import de.raindancer.core.world.farm.WorldSet;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a farm world may be called, and how many there may be.
 *
 * <h2>Why this is the most careful rule in the module</h2>
 * A farm world's name is a folder name, and regenerating a farm world deletes that folder. Core knows
 * this and refuses the dangerous names itself — {@code WorldSet}'s constructor throws for {@code world},
 * for a name with a slash in it and for one that climbs out with {@code ..} — and that check is the one
 * that stands between a typed command and a deleted server, so it is deliberately <b>not repeated
 * here</b>. A second copy of it is a copy that can be more permissive than the first, and the more
 * permissive of two answers is the one that runs.
 *
 * <p>What this adds is the two things Core cannot know: the words {@code /farm} reads as instructions,
 * and how many farm worlds this server has said it wants. Everything else is asked of Core, through
 * {@link #wouldCoreAllow} — asked rather than reimplemented, and asked without throwing, because a
 * rule has to be answerable speculatively so a screen can grey a button with it.
 *
 * <h2>Why {@link #RESERVED} is not tidiness</h2>
 * {@code /farm list} has to mean one of two things, and it means the subcommand — the command reads the
 * first word as one before it reads it as a name. So a farm world called {@code list} is a farm world
 * nothing can ever reach by typing: it appears in the menu, it can be clicked, and the command for it
 * prints a list instead. The list here is the command's own, imported by it rather than written twice.
 */
public final class FarmWorldNameRule implements IFarmWorldRule {

    /**
     * The words {@code /farm} reads as instructions.
     *
     * <p>The command takes its subcommands from this list, so adding one there cannot leave a farm
     * world name silently unreachable here.
     */
    public static final List<String> RESERVED = List.of(
            "list", "admin", "config", "settings", "create", "make", "delete", "remove", "regen",
            "regenerate", "info", "help", "reload");

    /**
     * What a name may be made of.
     *
     * <p>Deliberately narrower than {@code WorldSet}'s own rule, which allows a capital and lower-cases
     * it. Here a name is refused rather than quietly changed: a farm world an admin created as
     * {@code Nether} and then cannot find under that name looks like a command that did nothing, and
     * the folder on disk is the lower-cased one either way.
     */
    private static final Pattern TYPEABLE = Pattern.compile("[a-z0-9_-]+");

    /** What was wrong with a name, or that nothing was. */
    public enum Verdict {

        FINE(null),
        EMPTY("farmworlds.name.empty"),
        TOO_LONG("farmworlds.name.too-long"),
        BAD_CHARACTERS("farmworlds.name.bad-characters"),
        RESERVED("farmworlds.name.reserved"),
        /** Core refused it — one of the server's own worlds, or a name that is a path. */
        DANGEROUS("farmworlds.name.dangerous");

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

    /**
     * The longest a farm world's name may be.
     *
     * <p>Not a setting. It is a folder name and it is shown on a button, and neither of those is
     * something an owner should be able to make worse — a farm world whose name does not fit on its own
     * button is one nobody can pick out of a list of three.
     */
    public static final int LONGEST = 24;

    /** What, if anything, is wrong with this name. */
    public Verdict check(String name) {
        if (name == null || name.isBlank()) {
            return Verdict.EMPTY;
        }
        String trimmed = name.trim();
        if (trimmed.length() > LONGEST) {
            return Verdict.TOO_LONG;
        }
        if (!TYPEABLE.matcher(trimmed).matches()) {
            return Verdict.BAD_CHARACTERS;
        }
        // Case-insensitively, because the command matches its subcommands that way: /farm LIST is
        // still the list, so a farm world called LIST would still be unreachable.
        if (RESERVED.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return Verdict.RESERVED;
        }
        if (!wouldCoreAllow(trimmed)) {
            return Verdict.DANGEROUS;
        }
        return Verdict.FINE;
    }

    /**
     * Whether Core would accept this as a farm world's name.
     *
     * <p>The real check, asked rather than copied. {@code WorldSet} signals a refusal by throwing from
     * its constructor, which a rule may not do — {@link #check} is asked by a screen to decide whether
     * to grey a button, and a rule that threw would take the page down instead of greying anything.
     *
     * <p>Catching {@code IllegalArgumentException} and nothing wider on purpose: that is the one Core
     * throws for a refusal, and swallowing anything else would turn a real fault into a name that
     * merely looked invalid.
     */
    public boolean wouldCoreAllow(String name) {
        try {
            WorldSet.of(name);
            return true;
        } catch (IllegalArgumentException refused) {
            return false;
        }
    }

    /**
     * How many farm worlds one server may have.
     *
     * <p>Not a setting either, and for a stronger reason than the name length: each farm world is up to
     * three worlds held open, generated a chunk at a time as people scatter into them, and eventually
     * deleted. A server with forty of them has forty worlds' worth of memory and disk, and nothing
     * about typing the fortieth {@code create} would have said so. Eight is already more than any
     * server has a use for; an owner who genuinely wants nine wants a second server.
     */
    public static final int MOST = 8;

    /**
     * Whether there is room for one more.
     *
     * <p>Reaching the ceiling is a refusal with a line saying so, never a quiet nothing — the quiet
     * version is what gets reported as "making farm worlds has stopped working".
     */
    public boolean isRoomFor(int howManyThereAre) {
        return howManyThereAre < MOST;
    }

    /** The longest a name may be, for the message that says so. */
    public int longestName() {
        return LONGEST;
    }

    @Override
    public String describe() {
        return "what a farm world may be called, and how many there may be";
    }
}
