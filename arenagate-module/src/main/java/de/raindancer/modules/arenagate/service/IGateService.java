package de.raindancer.modules.arenagate.service;

import de.raindancer.modules.arenagate.GateSettings;

/** A service of this module. Takes the settings snapshot whether or not it reads anything from it yet. */
public interface IGateService {

    void settings(GateSettings settings);
}
