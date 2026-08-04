package de.raindancer.modules.pack.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>The whole membership rule for this package. Here it is what keeps the one piece of real logic in
 * the module — reading a hash out of a published file — testable without a network: the fetching is a
 * service's job, the parsing is this one's.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing fetched, nothing written, nothing sent.</li>
 *   <li><b>Safe from any thread.</b> The lookup runs off the server's threads and its answer is used
 *       on one of them.</li>
 *   <li><b>No server needed.</b> Plain values in, plain values out.</li>
 * </ul>
 */
public interface IPackRule {

    /** What this rule is about, for the diagnostic that names it. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
