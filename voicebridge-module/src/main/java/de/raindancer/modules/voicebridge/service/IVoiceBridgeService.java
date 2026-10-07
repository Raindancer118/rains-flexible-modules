package de.raindancer.modules.voicebridge.service;

import de.raindancer.modules.voicebridge.VoiceBridgeSettings;

/**
 * A service belonging to this module: does, and decides as little as possible. Takes every new
 * settings snapshot, whether or not it currently reads anything from it.
 */
public interface IVoiceBridgeService {

    void settings(VoiceBridgeSettings settings);
}
