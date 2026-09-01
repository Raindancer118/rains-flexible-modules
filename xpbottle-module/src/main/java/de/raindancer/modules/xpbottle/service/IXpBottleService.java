package de.raindancer.modules.xpbottle.service;

import de.raindancer.modules.xpbottle.XpBottleSettings;

/**
 * A service belonging to this module: does, and decides as little as possible.
 *
 * <p>Takes {@link #settings(XpBottleSettings)} and holds a snapshot of its own, whether or not it
 * currently reads anything from it — the service that is forgotten when it starts reading something
 * is the one that keeps yesterday's numbers until the next restart, and that gets reported as "the
 * config does not work".
 */
public interface IXpBottleService {

    void settings(XpBottleSettings settings);
}
