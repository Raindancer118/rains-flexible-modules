package de.raindancer.modules.voicebridge.model;

import java.util.UUID;

/** A code a player was shown in game, waiting to be typed into Discord. */
public record PendingLink(String code, UUID player, long expiresAt) {
}
