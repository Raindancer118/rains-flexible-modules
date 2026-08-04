package de.raindancer.modules.homes.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package. Here it earns its keep on the menu: every
 * home on the page asks whether it may be gone to, and again on the click, and the limit is asked on
 * every draw to write "3 of 5 used" on the counter. A rule that acted would put somebody on cooldown
 * for opening a window.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing written, nothing sent, nothing scheduled, and the home being
 *       judged is not changed by judging it.</li>
 *   <li><b>Safe from any thread.</b> On Folia the same rule instance is asked by every region at
 *       once.</li>
 *   <li><b>No server needed.</b> Every rule here takes plain values — a name, a count, a set of
 *       granted permissions — which is what makes the deciding half of this module testable. It is
 *       also the half that decides how many homes somebody gets, and that has been wrong before: see
 *       Core's {@code NumberedLimit} for the operator bug that gave every admin a hundred.</li>
 * </ul>
 */
public interface IHomeRule {

    /**
     * What this rule is about, in a few words.
     *
     * <p>Ends up in the diagnostic naming which rule refused something. Defaulted from the class name
     * so a rule cannot fail to have one.
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
