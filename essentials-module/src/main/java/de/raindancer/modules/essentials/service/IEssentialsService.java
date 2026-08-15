package de.raindancer.modules.essentials.service;

import de.raindancer.modules.essentials.EssentialsSettings;

/**
 * Something that <em>does</em> what a command asked for.
 *
 * <p>Every service takes its settings through {@link #settings(EssentialsSettings)} whether or not it
 * currently reads anything — the one forgotten when it starts reading something keeps yesterday's
 * numbers until the next restart, and that gets reported as "the config does not work".
 */
public interface IEssentialsService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(EssentialsSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
