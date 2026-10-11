package de.raindancer.modules.sends.service;

import de.raindancer.modules.sends.SendsSettings;

/**
 * Does, and decides as little as possible: every refusal is a rule's, and every change of state is the book's.
 * Holds a snapshot of the settings, handed over again whenever they change.
 */
public interface ISendsService {

    void settings(SendsSettings settings);
}
