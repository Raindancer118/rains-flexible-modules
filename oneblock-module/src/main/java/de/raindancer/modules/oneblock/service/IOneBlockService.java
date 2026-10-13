package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.OneBlockSettings;

/**
 * Does, and decides as little as possible: every refusal is a rule's. Holds a snapshot of the settings, handed over
 * again whenever they change.
 */
public interface IOneBlockService {

    void settings(OneBlockSettings settings);
}
