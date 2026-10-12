package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.ArenaSettings;

/** A service of this module. Takes the settings snapshot whether or not it reads anything from it yet. */
public interface IArenaService {

    void settings(ArenaSettings settings);
}
