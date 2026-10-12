package de.raindancer.modules.arenagate.model;

import java.util.UUID;

/** One player's stake for one fight, held by the server until the fight is settled. */
public record Held(String fight, UUID player, long amount, long created) {
}
