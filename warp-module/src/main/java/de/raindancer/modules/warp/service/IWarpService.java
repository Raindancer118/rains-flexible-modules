package de.raindancer.modules.warp.service;

import de.raindancer.modules.warp.WarpSettings;

/**
 * Something that <em>does</em> what a command or a click asked for.
 *
 * <p>The counterpart to {@link de.raindancer.modules.warp.rules.IWarpRule}: a rule decides and
 * changes nothing, a service changes things and decides as little as possible. Sending somebody
 * somewhere, making a warp, moving one — each has an effect, and each asks the rule first rather
 * than working the answer out again.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(WarpSettings)}</b> rather than holding a
 *       live view — and every service takes it <em>whether or not it currently reads anything</em>.
 *       The one forgotten when it starts reading something is the one that keeps yesterday's
 *       numbers until the next restart, and that gets reported as "the config does not work".</li>
 *   <li><b>It asks rather than decides.</b> A second answer to "may they use this warp" is the one
 *       that opens the staff room.</li>
 *   <li><b>It runs on the thread that owns what it touches, or it schedules onto it.</b> A teleport
 *       belongs to the destination's region, which on Folia is not the one a command runs on.</li>
 * </ul>
 */
public interface IWarpService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(WarpSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
