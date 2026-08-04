package de.raindancer.modules.tpa.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package. Here it matters because the menu asks the same
 * questions the commands do — whether this person may be asked, whether that button should be grey — and
 * a rule that acted would make a request every time somebody opened a window.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing written, nothing sent, nothing scheduled.</li>
 *   <li><b>Safe from any thread.</b> On Folia the same rule instance is asked by every region at once.</li>
 *   <li><b>No server needed.</b> Every rule here takes plain values — a uuid, a {@code TpaPrefs}, a
 *       handful of booleans — never a {@code Player}. That is what makes the deciding half of this
 *       module testable, and it is the half that decides whether somebody who asked to be left alone
 *       is left alone.</li>
 * </ul>
 */
public interface ITpaRule {

    /**
     * What this rule is about, in a few words.
     *
     * <p>Ends up in the diagnostic naming which rule refused something. Defaulted from the class name so
     * a rule cannot fail to have one.
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
