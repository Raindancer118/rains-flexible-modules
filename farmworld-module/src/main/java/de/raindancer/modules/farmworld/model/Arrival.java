package de.raindancer.modules.farmworld.model;

import java.util.Locale;
import java.util.Set;

/**
 * Which of the two ways into a farm world a trip is asking for.
 *
 * <h2>Why there are two, and why the plain one is the spawn</h2>
 * Scattering everybody was the first design of this module, and it is wrong as a <em>default</em> for a
 * reason that only shows up once you are standing in it: a random point is a place with no way back and
 * nothing around it. A farm world's spawn is where an admin builds the platform, the portals and the sign
 * saying when it is regenerated — and dropping people thousands of blocks away from all of that means none
 * of it is ever seen.
 *
 * <p>So a plain {@code /farm mining} is predictable, and {@code /farm mining rtp} is how somebody asks to be
 * put down where nobody has been. The scattering itself is unchanged, and it is still what makes the far
 * parts of a farm world worth having — it is only no longer what happens to somebody who did not ask.
 *
 * <h2>Why an unknown word is the spawn rather than a refusal</h2>
 * Because the two wrong answers are not equally wrong. Reading {@code wilf} as a request to be scattered
 * costs somebody a walk home from four thousand blocks out; reading it as "go to the farm world" puts them
 * somewhere with a way back. The safe reading wins, and the command says which it did.
 */
public enum Arrival {

    /** The farm world's own spawn, where the platform is. What a plain trip means. */
    SPAWN,

    /** Somewhere nobody has been, in the ring. What {@code rtp} means. */
    WILD;

    /**
     * The words that mean "send me into the wild".
     *
     * <p>Several, because people arrive from different servers with different vocabulary and there is no
     * reason to make them learn this one's. {@code rtp} is what most plugins call it, {@code wild} is what
     * most players call it, and the other two are what somebody guesses.
     */
    private static final Set<String> WILD_WORDS =
            Set.of("rtp", "wild", "random", "scatter");

    /** What a word after the farm world's name is asking for. */
    public static Arrival of(String word) {
        if (word == null || word.isBlank()) {
            return SPAWN;
        }
        return WILD_WORDS.contains(word.trim().toLowerCase(Locale.ROOT)) ? WILD : SPAWN;
    }

    /** Whether this arrival puts somebody down somewhere unpredictable. */
    public boolean isScattered() {
        return this == WILD;
    }

    /**
     * Whether the server offers this at all.
     *
     * <p>{@code scatter-arrivals} now means "may people ask to be sent into the wild" rather than "is every
     * arrival random". A server with it off has a farm world people walk out of, and asking for {@code rtp}
     * there is refused out loud rather than quietly turned into an ordinary trip — a command that silently
     * does something else is one people type four more times.
     *
     * <p>Going to the spawn is always allowed. Otherwise switching scattering off would switch the farm
     * world off, which is the opposite of what an owner asking for predictable arrivals wants.
     */
    public boolean isAllowedBy(Scatter scatter) {
        return this == SPAWN || (scatter != null && scatter.isOn());
    }

    /** The words a tab completion should offer. */
    public static java.util.List<String> words() {
        return java.util.List.of("rtp", "wild");
    }
}
