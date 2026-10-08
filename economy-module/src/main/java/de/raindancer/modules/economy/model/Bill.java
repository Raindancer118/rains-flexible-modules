package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/** One player asking another to pay them. Held in memory; it expires rather than lingering. */
public record Bill(UUID id, UUID from, String fromName, UUID to, Money amount, String reason, long expiresAt) {

    public boolean expired(long now) {
        return now >= expiresAt;
    }
}
