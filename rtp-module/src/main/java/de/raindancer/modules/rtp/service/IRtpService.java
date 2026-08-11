package de.raindancer.modules.rtp.service;

import de.raindancer.modules.rtp.RtpSettings;

/**
 * A service belonging to this module: does, and decides as little as possible.
 *
 * <p>Takes {@link #settings(RtpSettings)} and holds a snapshot, whether or not it currently reads
 * anything from it. The service forgotten when it starts reading something is the one that keeps
 * yesterday's radius or cooldown until the next restart, which gets reported as "the config does not
 * work".
 */
public interface IRtpService {

    void settings(RtpSettings settings);

    /** What this service does, for a diagnostic. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
