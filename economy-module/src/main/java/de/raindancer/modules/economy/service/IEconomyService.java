package de.raindancer.modules.economy.service;

import de.raindancer.modules.economy.EconomySettings;

/**
 * A service belonging to this module: does, and decides as little as possible. Holds a snapshot of the
 * settings whether or not it reads them today, so a reload never leaves one on yesterday's numbers.
 */
public interface IEconomyService {

    void settings(EconomySettings settings);
}
