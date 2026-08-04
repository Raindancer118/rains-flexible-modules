package de.raindancer.modules.warp.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package, and here it earns its keep the moment a
 * menu is drawn: every warp on the page asks "may this player use it" to decide whether to grey the
 * button, and again on the click. A rule that acted would put somebody on cooldown for opening a
 * window.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing written, nothing sent, nothing scheduled, and the warp being
 *       judged is not changed by judging it.</li>
 *   <li><b>Safe from any thread.</b> On Folia the same warp is asked about by every region at once,
 *       and there is one instance of each of these.</li>
 *   <li><b>No server needed.</b> Every rule here takes plain values — a {@code WarpAccess} and a
 *       predicate, never a {@code Player} — which is what makes the deciding half of this module
 *       testable, and it is the half that determines who can reach the staff warps.</li>
 * </ul>
 */
public interface IWarpRule {

    /**
     * What this rule is about, in a few words.
     *
     * <p>Ends up in the diagnostic naming which rule refused something. Defaulted from the class
     * name so a rule cannot fail to have one.
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
