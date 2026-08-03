package de.raindancer.modules.claims.service;

import de.raindancer.modules.claims.ClaimSettings;

/**
 * Something that <em>does</em> things to the world on a claim's behalf.
 *
 * <p>The counterpart to {@link de.raindancer.modules.claims.rules.IClaimRule}: a rule decides and changes
 * nothing, a service changes things and decides as little as possible. Building a fence, taking a toll, feeding
 * somebody from a pantry, walking a banned player out — each is an action with an effect on the world, and each
 * asks a rule first rather than inventing its own answer.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(ClaimSettings)}</b> rather than holding a live view.
 *       A snapshot means nothing has to reason about a value changing halfway through a calculation, and
 *       swapping the whole snapshot means a reload takes effect on the next event rather than the next
 *       restart.</li>
 *   <li><b>It asks rather than decides.</b> A service that works out for itself whether somebody may do
 *       something is a second set of rules, and the second set is always the one that is wrong.</li>
 *   <li><b>It is safe to call from a region thread</b>, or it schedules — on Folia every player has their own,
 *       and a service that touches an inventory from the wrong one corrupts it.</li>
 * </ul>
 */
public interface IClaimService {

    /**
     * Swaps in the settings as they are now. Called on reload.
     *
     * <p>Every service takes this whether or not it currently reads anything from the file, because the one
     * that is forgotten when it starts reading something is the one that keeps yesterday's numbers until the
     * next restart — and that gets reported as "the config does not work".
     */
    void settings(ClaimSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
