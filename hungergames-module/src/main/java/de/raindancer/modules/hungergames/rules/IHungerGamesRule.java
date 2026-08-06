package de.raindancer.modules.hungergames.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package, and in this module it earns its keep more than
 * anywhere else in the repository. A round is one long argument about what is allowed right now: may this
 * tribute break that block, may that team take this colour, has somebody won, is the border allowed to move
 * that fast, does the start sequence pass its checks. Every one of those questions is asked speculatively —
 * to grey a button, to draw a preflight list, to decide whether a menu may offer something — long before
 * anybody acts on the answer.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing written, nothing sent, nothing scheduled, and the thing being judged
 *       is not changed by judging it. The protection matrix is the one to watch: it is asked on every block
 *       break, every bucket, every piston, several times a tick, by whichever region thread happens to own
 *       the block.</li>
 *   <li><b>Safe from any thread.</b> On Folia the border timer, a tribute's click and a block break are three
 *       different threads, and there is one instance of each of these.</li>
 *   <li><b>No server needed.</b> Every rule here takes plain values — a phase, a UUID, a set of colours, a
 *       number of blocks — never a {@code Player}, a {@code World} or an {@code Event}. Which is what makes
 *       the deciding half of a Hunger Games round testable without booting Paper, and that half includes
 *       who won.</li>
 * </ul>
 *
 * <p>Where a rule is really a chain of reasons — the eleven preflight checks, which each have to say their
 * own sentence — use Core's {@code IRule<T>} / {@code AbstractRule<T>} / {@code Rules<T>} instead of
 * inventing a second chain here.
 */
public interface IHungerGamesRule {

    /**
     * What this rule is about, in a few words.
     *
     * <p>Ends up in the diagnostic naming which rule refused something. Defaulted from the class name so a
     * rule cannot fail to have one.
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
