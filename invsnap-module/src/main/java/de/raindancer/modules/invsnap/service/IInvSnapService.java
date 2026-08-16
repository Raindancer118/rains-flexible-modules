package de.raindancer.modules.invsnap.service;

import de.raindancer.modules.invsnap.InvSnapSettings;

/**
 * A service belonging to this module: does, and decides as little as possible.
 *
 * <p>Takes {@link #settings(InvSnapSettings)} and holds a snapshot of its own, whether or not
 * it currently reads anything from it — the service that is forgotten when it starts reading
 * something is the one that keeps yesterday's numbers until the next restart.
 */
public interface IInvSnapService {

    void settings(InvSnapSettings settings);
}
