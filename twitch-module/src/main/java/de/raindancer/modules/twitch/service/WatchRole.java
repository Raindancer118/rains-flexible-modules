package de.raindancer.modules.twitch.service;

import de.raindancer.core.platform.network.NetworkRoles;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Whether this server is the one that asks Twitch. On a network that is a lease in the database — one server holds it,
 * renews it from {@link #tick()}, and another takes over when the holder stops renewing; a server of its own always
 * holds it. {@code gained} and {@code lost} run on the thread that calls {@link #tick()}.
 */
public final class WatchRole {

    public static final String ROLE = "twitch";

    private final NetworkRoles roles;
    private final BooleanSupplier wanted;
    private final Runnable gained;
    private final Runnable lost;
    private boolean holding;

    /**
     * @param roles  the network's roles; null on a server of its own
     * @param wanted whether this server can watch at all (it has the login, announcements are on): a server that
     *               cannot must not hold the role, or nobody would ask
     */
    public WatchRole(NetworkRoles roles, BooleanSupplier wanted, Runnable gained, Runnable lost) {
        this.roles = roles;
        this.wanted = wanted;
        this.gained = gained;
        this.lost = lost;
    }

    /** Claims or renews the role, or lets it go when this server can no longer watch. Call it every few seconds. */
    public synchronized void tick() {
        boolean can = wanted.getAsBoolean();
        boolean now = can && (roles == null || roles.claim(ROLE));
        if (holding && !can && roles != null) {
            roles.release(ROLE);
        }
        move(now);
    }

    /** Gives the role up at once — at shutdown — so another server does not wait out the lease. */
    public synchronized void stop() {
        if (roles != null && holding) {
            roles.release(ROLE);
        }
        move(false);
    }

    /** Runs {@code gained} again when this server holds the role: what it watches with has changed. */
    public synchronized void refresh() {
        if (holding) {
            gained.run();
        }
    }

    public synchronized boolean holds() {
        return holding;
    }

    /** The server that watches, as far as the database says. */
    public Optional<String> holder() {
        return roles == null ? Optional.empty() : roles.holder(ROLE);
    }

    private void move(boolean now) {
        if (now == holding) {
            return;
        }
        holding = now;
        (now ? gained : lost).run();
    }
}
