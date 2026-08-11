package de.raindancer.modules.chained.service;

import de.raindancer.modules.chained.ChainedSettings;

/**
 * Something that <em>does</em> what a command or a click asked for.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(ChainedSettings)}</b> rather than holding
 *       a live view — and every service takes it whether or not it currently reads anything from
 *       the file. The one forgotten when it starts reading something is the one that keeps
 *       yesterday's numbers until the next restart, and that gets reported as "the config does not
 *       work".</li>
 *   <li><b>It asks rather than decides.</b> A second answer to "would this move separate them too
 *       far" is the one that opens a hole in the wall.</li>
 *   <li><b>It runs on the thread that owns what it touches, or it schedules onto it.</b></li>
 * </ul>
 */
public interface IChainedService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(ChainedSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
