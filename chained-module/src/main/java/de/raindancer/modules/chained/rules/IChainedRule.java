package de.raindancer.modules.chained.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing written, nothing sent, nothing scheduled, and the thing
 *       being judged is not changed by judging it.</li>
 *   <li><b>Safe from any thread.</b> {@link de.raindancer.modules.chained.rules.ChainDistanceRule}
 *       is asked on every changed-block move of every chained player, which on Folia can be several
 *       region threads at once.</li>
 *   <li><b>No server needed.</b> Every rule here takes plain values, never a {@code Player} — which
 *       is what makes the deciding half of this module testable without booting one.</li>
 * </ul>
 */
public interface IChainedRule {

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
