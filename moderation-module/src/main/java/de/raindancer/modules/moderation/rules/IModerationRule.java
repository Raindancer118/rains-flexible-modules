package de.raindancer.modules.moderation.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package. The alternative — a rule that also records,
 * announces or kicks — is one nothing can ask speculatively, and speculation is most of what a
 * moderation screen does: the punish button has to know what it would do before it is pressed, so that
 * it can say so in its own lore and grey itself when the answer is "you may not".
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing recorded, nothing sent, nothing scheduled, and the thing being
 *       judged is not changed by judging it.</li>
 *   <li><b>Safe from any thread.</b> A report arrives from a chat event, which Paper fires
 *       asynchronously; the screen that lists reports renders on the server thread; on Folia a staff
 *       message can be sent from any region thread at all.</li>
 *   <li><b>No server needed.</b> Every rule here takes ids and values, never a {@code Player} — which
 *       is what makes the interesting half of this module testable at all.</li>
 * </ul>
 *
 * <p>Anything failing those belongs elsewhere: what a punishment <em>is</em> goes in {@code model},
 * what holds or writes it in {@code store}, and what acts in {@code service}.
 *
 * <h2>Its relation to Core's rules</h2>
 * {@link de.raindancer.core.platform.rule.IRule} is the generic form — one question, one
 * {@link de.raindancer.core.platform.rule.Verdict}, chained by {@code Rules}. The rules here answer
 * {@code Verdict} too, so a refusal reads the same wherever it came from; they are not chained because
 * each asks a differently shaped question, and forcing them through one {@code judge(T)} would mean
 * inventing a wrapper type per question for nothing.
 */
public interface IModerationRule {

    /**
     * What this rule is about, in a few words.
     *
     * <p>Ends up in the diagnostic naming which rule refused something. Defaulted from the class name
     * so a rule cannot fail to have one — but the default reads as {@code StaffRule}, and something
     * that reads in a sentence is worth the line.
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
