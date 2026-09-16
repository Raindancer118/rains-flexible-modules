package de.raindancer.modules.worldutils.service;

import de.raindancer.modules.worldutils.WorldUtilsSettings;

/**
 * Something that <em>does</em>: a rule decides and changes nothing, a service changes things and
 * decides as little as possible.
 */
public interface IWorldUtilsService {

    /** Swaps in the settings as they are now. Called on reload — see {@code IWorldGateService} on why. */
    void settings(WorldUtilsSettings settings);

    /** What this service does, for a diagnostic. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
