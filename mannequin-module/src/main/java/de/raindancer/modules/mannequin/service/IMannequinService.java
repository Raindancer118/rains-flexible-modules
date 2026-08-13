package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.MannequinSettings;

/**
 * A service belonging to this module.
 *
 * <p>{@link #settings} is on every implementation, whether or not it currently reads anything from
 * the settings — a service that only starts reading a value later must already have somewhere to
 * put it, or it keeps yesterday's number until the next restart. See {@code MODULE-LAYOUT.md}.
 */
public interface IMannequinService {

    void settings(MannequinSettings settings);
}
