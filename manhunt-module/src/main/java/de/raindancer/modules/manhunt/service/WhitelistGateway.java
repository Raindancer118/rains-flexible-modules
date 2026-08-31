package de.raindancer.modules.manhunt.service;

import java.util.Collection;
import java.util.UUID;

/**
 * The one seam {@link ManhuntWhitelistService} reaches through to touch the server's real whitelist.
 *
 * <p>Exists so {@code open()}/{@code close()}'s actual decision — who to add, and in which order to
 * flip the enabled flag — is a plain unit test against a fake, rather than something only a live
 * Paper server can exercise. {@link BukkitWhitelistGateway} is the only real implementation.
 */
interface WhitelistGateway {

    /** Everybody on the server right now. */
    Collection<UUID> onlinePlayerIds();

    boolean isWhitelisted(UUID id);

    void setWhitelisted(UUID id, boolean whitelisted);

    boolean isWhitelistEnabled();

    void setWhitelistEnabled(boolean enabled);
}
