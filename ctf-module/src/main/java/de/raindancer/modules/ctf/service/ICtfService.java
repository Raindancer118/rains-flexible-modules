package de.raindancer.modules.ctf.service;

import de.raindancer.modules.ctf.CtfSettings;

/**
 * Does, and decides as little as possible. Takes every new settings snapshot whether or not it reads anything from it
 * today — the one forgotten is the one that keeps yesterday's numbers until a restart. See MODULE-LAYOUT.md.
 */
public interface ICtfService {

    void settings(CtfSettings settings);
}
