package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.AntiCheatSettings;

/** Does, and decides as little as possible. Holds a settings snapshot, swapped on reload. */
public interface IAntiCheatService {

    void settings(AntiCheatSettings settings);

    String describe();
}
