package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;

/**
 * Something that <em>does</em> what a command, a click or a timer asked for.
 *
 * <p>The counterpart to {@link de.raindancer.modules.hungergames.rules.IHungerGamesRule}: a rule decides and
 * changes nothing, a service changes things and decides as little as possible.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(HungerGamesSettings)}</b> rather than holding a live
 *       view — and every service takes it <em>whether or not it currently reads anything</em>. This module has
 *       more services than any other in the repository and a config page that writes while a round is running,
 *       so the one forgotten here is the one that keeps yesterday's numbers for the rest of the tournament.
 *       A service with nothing to swap implements it empty and says why.</li>
 *   <li><b>It asks rather than decides.</b> A second answer to "has somebody won" ends a round early, in front
 *       of everybody, and there is no undoing it.</li>
 *   <li><b>It runs on the thread that owns what it touches, or it schedules onto it.</b> Moving the world
 *       border belongs to the world; putting a tribute in spectator belongs to that tribute; announcing to the
 *       server belongs to the global region. On Folia those are three different threads and none of them is
 *       the one a command arrives on.</li>
 * </ul>
 */
public interface IHungerGamesService {

    /** Swaps in the settings as they are now. Called on reload, and while a round is running. */
    void settings(HungerGamesSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
