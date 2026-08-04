package de.raindancer.modules.farmworld.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package. Here it earns its keep twice over: every
 * farm world on the list asks "may this player enter it" to decide whether to grey the button and
 * again on the click, and the warning timer asks "is a notice due" once every twenty seconds for
 * every farm world on the server.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing written, nothing sent, nothing scheduled, and the farm world
 *       being judged is not changed by judging it. The warning rule is the one to watch: a rule that
 *       remembered what it had already announced would announce it once and then be wrong for every
 *       other farm world on the server, so remembering is the service's job and deciding is
 *       this one's.</li>
 *   <li><b>Safe from any thread.</b> On Folia the timer and a player's click are not the same thread,
 *       and there is one instance of each of these.</li>
 *   <li><b>No server needed.</b> Every rule here takes plain values — a name, a {@code Duration}, a
 *       predicate, never a {@code Player} or a {@code World}. Which is what makes the deciding half
 *       of this module testable, and that half includes the one decision that ends with three worlds
 *       being deleted.</li>
 * </ul>
 */
public interface IFarmWorldRule {

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
