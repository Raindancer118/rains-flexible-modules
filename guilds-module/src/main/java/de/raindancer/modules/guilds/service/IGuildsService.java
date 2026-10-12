package de.raindancer.modules.guilds.service;

import de.raindancer.modules.guilds.GuildsSettings;

/**
 * Does, and decides as little as possible — it asks a rule. Holds a settings snapshot whether or not it reads
 * one today, so the first setting it starts reading is never stuck at yesterday's value. See MODULE-LAYOUT.md.
 */
public interface IGuildsService {

    void settings(GuildsSettings settings);
}
