package de.raindancer.modules.performance.service;

import de.raindancer.modules.performance.PerformanceSettings;

/**
 * A service of this module: does, and decides as little as possible. Takes the settings as they change,
 * so a value changed in /settings reaches it without a restart.
 */
public interface IPerformanceService {

    void settings(PerformanceSettings settings);

    default String describe() {
        return getClass().getSimpleName();
    }
}
