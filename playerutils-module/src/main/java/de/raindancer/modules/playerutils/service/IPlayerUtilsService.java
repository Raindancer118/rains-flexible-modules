package de.raindancer.modules.playerutils.service;

import de.raindancer.modules.playerutils.PlayerUtilsSettings;

/**
 * Does, and decides as little as possible. Takes every settings change, whether or not it reads anything
 * from them today — the service that is forgotten when it starts to is the one that keeps yesterday's
 * numbers until a restart.
 */
public interface IPlayerUtilsService {

    void settings(PlayerUtilsSettings settings);

    String describe();
}
