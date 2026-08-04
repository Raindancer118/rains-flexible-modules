package de.raindancer.modules.tpa.service;

import de.raindancer.modules.tpa.TpaSettings;

/**
 * Something that <em>does</em> what a command or a click asked for.
 *
 * <p>The counterpart to {@link de.raindancer.modules.tpa.rules.ITpaRule}: a rule decides and changes
 * nothing, a service changes things and decides as little as possible.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(TpaSettings)}</b> rather than holding a live
 *       view — and every service takes it <em>whether or not it currently reads anything</em>. The one
 *       forgotten when it starts reading something keeps yesterday's numbers until the next restart,
 *       and that gets reported as "the config does not work".</li>
 *   <li><b>It asks rather than decides.</b> A second answer to "may they ask this person" is the one
 *       that gets through to somebody who asked to be left alone.</li>
 *   <li><b>It runs on the thread that owns what it touches, or it schedules onto it.</b></li>
 * </ul>
 */
public interface ITpaService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(TpaSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
