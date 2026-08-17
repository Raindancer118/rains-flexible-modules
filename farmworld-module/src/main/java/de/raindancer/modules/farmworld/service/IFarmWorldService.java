package de.raindancer.modules.farmworld.service;

import de.raindancer.modules.farmworld.FarmWorldSettings;

/**
 * Something that <em>does</em> what a command, a click or the timer asked for.
 *
 * <p>The counterpart to {@link de.raindancer.modules.farmworld.rules.IFarmWorldRule}: a rule decides and
 * changes nothing, a service changes things and decides as little as possible.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(FarmWorldSettings)}</b> rather than holding a
 *       live view — and every service takes it <em>whether or not it currently reads anything</em>. The
 *       one forgotten when it starts reading something is the one that keeps yesterday's numbers until
 *       the next restart, and that gets reported as "the config does not work".</li>
 *   <li><b>It asks rather than decides.</b> A second answer to "may they enter this farm world" is the
 *       one that opens the donor world to everybody.</li>
 *   <li><b>It runs on the thread that owns what it touches, or it schedules onto it.</b> A teleport
 *       belongs to the destination's region; creating and deleting a world belongs to the global
 *       region (Scheduling.global) and nowhere else.</li>
 * </ul>
 */
public interface IFarmWorldService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(FarmWorldSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
