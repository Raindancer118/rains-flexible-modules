package de.raindancer.modules.names.service;

import de.raindancer.modules.names.NamesSettings;

/**
 * Something that <em>does</em> what a craft or an interaction asked for.
 *
 * <p>The counterpart to {@link de.raindancer.modules.names.rules.INamesRule}: a rule decides and changes
 * nothing, a service changes things and decides as little as possible. Building the result item,
 * charging the grid for it, washing a tag, painting a mob — each is an action with an effect, and each
 * asks the rule first rather than inventing its own answer.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(NamesSettings)}</b> rather than holding a live
 *       view. Every service takes it <em>whether or not it currently reads anything from the file</em>,
 *       because the one that is forgotten when it starts reading something is the one that keeps
 *       yesterday's numbers until the next restart — and that gets reported as "the config does not
 *       work".</li>
 *   <li><b>It asks rather than decides.</b> A service that works out for itself what a grid means is a
 *       second set of recipes, and the second set is always the one that is wrong — here it would be
 *       the one charging for an item the preview never offered.</li>
 *   <li><b>It runs on the thread that owns what it touches, or it schedules onto it.</b> A crafting grid
 *       belongs to a player and a mob's name to that mob, which on Folia are two different threads.</li>
 * </ul>
 */
public interface INamesService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(NamesSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
