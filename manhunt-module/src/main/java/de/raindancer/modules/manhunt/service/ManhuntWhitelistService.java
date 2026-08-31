package de.raindancer.modules.manhunt.service;

import org.bukkit.Server;

import java.util.Objects;
import java.util.UUID;

/**
 * "Open" and "close" for the server's real whitelist — {@code Bukkit.setWhitelist}, not a match-local
 * roster — matching exactly what was asked for: <b>open</b> means anybody can join; <b>close</b>
 * means whoever is online right now stays whitelisted and nobody else gets in.
 *
 * <h2>Why closing snapshots the online roster rather than just flipping the flag</h2>
 * Turning the whitelist on with nothing on it locks out the very players it is supposed to protect —
 * a Manhunt closing its own doors to its own Runners and Hunters mid-hunt. So {@link #close} adds
 * everybody currently online to the whitelist first (never removing anybody already on it for some
 * other reason) and only then turns the flag on. The order matters: flipping the flag first would
 * make the add-loop's own lookups briefly subject to the whitelist it is still building.
 *
 * <h2>Why nobody is ever removed</h2>
 * Somebody the server owner whitelisted by hand, for a reason that has nothing to do with this match,
 * does not lose that entry because a Manhunt closed its doors — {@link #close} only ever adds.
 * {@link #open} does not touch individual entries at all, only the server-wide flag, so a later
 * {@link #close} still finds every name this or an earlier close ever added.
 */
public final class ManhuntWhitelistService {

    private final WhitelistGateway gateway;

    public ManhuntWhitelistService(Server server) {
        this(new BukkitWhitelistGateway(Objects.requireNonNull(server, "server")));
    }

    /** For tests: a fake gateway that never touches a live server. */
    ManhuntWhitelistService(WhitelistGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** Anybody can join again. Existing whitelist entries are left exactly as they were. */
    public void open() {
        gateway.setWhitelistEnabled(false);
    }

    /**
     * Whoever is online right now is whitelisted, and the server is shut to anybody else.
     *
     * @return how many players were newly added — for the confirmation a command or a menu shows
     */
    public int close() {
        int added = 0;
        for (UUID id : gateway.onlinePlayerIds()) {
            if (!gateway.isWhitelisted(id)) {
                gateway.setWhitelisted(id, true);
                added++;
            }
        }
        gateway.setWhitelistEnabled(true);
        return added;
    }

    /** Whether the server whitelist is currently on. */
    public boolean isClosed() {
        return gateway.isWhitelistEnabled();
    }
}
