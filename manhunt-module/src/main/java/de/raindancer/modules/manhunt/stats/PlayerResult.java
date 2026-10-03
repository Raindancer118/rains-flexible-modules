package de.raindancer.modules.manhunt.stats;

import java.util.UUID;

/**
 * One player's hunt.
 *
 * @param runner         the side they finished on
 * @param survivedMillis for a Runner, how long until they were caught — the whole hunt if never
 * @param distance       blocks travelled, sampled once a second, teleports left out
 */
public record PlayerResult(UUID id, String name, boolean runner, boolean won, boolean caught, int catches,
                           int deaths, long survivedMillis, double distance, int portals) {
}
