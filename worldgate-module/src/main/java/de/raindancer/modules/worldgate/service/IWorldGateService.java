package de.raindancer.modules.worldgate.service;

import de.raindancer.modules.worldgate.WorldGateSettings;

/**
 * Something that <em>does</em> what the module was configured to do.
 *
 * <p>The counterpart to {@link de.raindancer.modules.worldgate.rules.IWorldGateRule}: a rule decides
 * and changes nothing, a service changes things and decides as little as possible.
 */
public interface IWorldGateService {

    /** Swaps in the settings as they are now. Called on reload — the one forgotten when it starts
     *  reading something is the one that keeps yesterday's world names until the next restart. */
    void settings(WorldGateSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
