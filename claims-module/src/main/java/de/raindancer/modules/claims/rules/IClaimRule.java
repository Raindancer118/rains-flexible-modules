package de.raindancer.modules.claims.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package, and it is worth naming because the alternative — a rule
 * that also saves, sends a message or starts a timer — is one nothing can ask speculatively. "Would this be
 * allowed?" is exactly what a screen asks to decide whether to grey a button, and it has to be free to ask.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing saved, nothing sent, nothing scheduled, and the thing being judged is
 *       not changed by judging it.</li>
 *   <li><b>Safe from any thread.</b> Claims are asked about from every region thread on a Folia server, several
 *       times a tick.</li>
 *   <li><b>Cheap, or honest about not being.</b> A rule that walks a spatial index says so in
 *       {@link #describe()} and is ordered late in whatever chain holds it.</li>
 * </ul>
 *
 * <p>Anything that fails those belongs elsewhere: what a claim <em>is</em> goes in {@code model}, what stores or
 * hands it over goes in {@code store}, and what acts on the world goes in {@code service}.
 *
 * <h2>Its relation to Core's rules</h2>
 * {@link de.raindancer.core.platform.rule.IRule} is the generic form — one question, one {@code Verdict}, chained
 * by {@code Rules}. The claim-creation checks are built on it, because those genuinely are a chain a server
 * should be able to lengthen.
 *
 * <p>This is the module's own, smaller promise, and it covers the deciders that are <em>not</em> a chain: the
 * per-player permission answer, the feature policy, the management rights. Each of those has a shape of its own —
 * one takes a player and an action, another takes a feature — and forcing them through a single {@code judge(T)}
 * would mean inventing a wrapper type per question for nothing. What they share is the contract above.
 */
public interface IClaimRule {

    /**
     * What this rule is about, in a few words.
     *
     * <p>Ends up in the diagnostic naming which rule refused something, and in the admin screen that lists what a
     * claim is judged by. Defaulted from the class name so a rule cannot fail to have one — but the default reads
     * as {@code ClaimRightsRule}, and something that reads in a sentence is worth the line.
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
