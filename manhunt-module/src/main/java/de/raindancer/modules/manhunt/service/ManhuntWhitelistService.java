package de.raindancer.modules.manhunt.service;

import org.bukkit.Server;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
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
    private final WhitelistVips vips;

    public ManhuntWhitelistService(Server server, WhitelistVips vips) {
        this(new BukkitWhitelistGateway(Objects.requireNonNull(server, "server")), vips);
    }

    /** For tests: a fake gateway that never touches a live server. */
    ManhuntWhitelistService(WhitelistGateway gateway, WhitelistVips vips) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.vips = Objects.requireNonNull(vips, "vips");
    }

    /** The people every one of these operations spares — see {@link WhitelistVips}. */
    public WhitelistVips vips() {
        return vips;
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
        Set<UUID> letIn = new LinkedHashSet<>(gateway.onlinePlayerIds());
        // A VIP is let in whether or not they were standing here when the door shut — that is the
        // whole of what being one means, and it is the case the online sweep cannot cover.
        letIn.addAll(vips.ids());
        for (UUID id : letIn) {
            if (!gateway.isWhitelisted(id)) {
                gateway.setWhitelisted(id, true);
                added++;
            }
        }
        gateway.setWhitelistEnabled(true);
        return added;
    }

    /**
     * Takes everybody off the whitelist except the VIPs, and leaves the door itself exactly as open
     * or shut as it was.
     *
     * <h2>Why the flag is not touched</h2>
     * "Clear" is about who is on the list, not about whether the list is being enforced — and the two
     * are different decisions with very different consequences. Turning the flag off as well would
     * silently throw the server open at the moment its list was emptied; turning it on would lock out
     * everybody who is playing right now. Whoever wants either types {@code open} or {@code close}.
     *
     * @return how many entries were removed — for the confirmation a command shows
     */
    public int clear() {
        int removed = 0;
        for (UUID id : gateway.whitelistedIds()) {
            if (vips.isVip(id)) {
                continue;
            }
            gateway.setWhitelisted(id, false);
            removed++;
        }
        return removed;
    }

    /**
     * Makes {@code id} a VIP — and, when the door is already shut, lets them in at once rather than
     * at the next close, which is the whole reason somebody is made one mid-evening.
     *
     * @return whether they were not already one
     */
    public boolean addVip(UUID id, String name) {
        boolean fresh = vips.add(id, name);
        if (isClosed() && !gateway.isWhitelisted(id)) {
            gateway.setWhitelisted(id, true);
        }
        return fresh;
    }

    /**
     * Takes the badge off {@code id}, and nothing else: their whitelist entry, if they have one,
     * stays. Removing somebody from the server is {@code /whitelist remove}'s job, and a VIP being
     * demoted mid-evening should not be kicked out of the round they are in the middle of — the next
     * {@link #clear()} simply no longer spares them.
     *
     * @return whether they were a VIP at all
     */
    public boolean removeVip(UUID id) {
        return vips.remove(id);
    }

    /** Whether the server whitelist is currently on. */
    public boolean isClosed() {
        return gateway.isWhitelistEnabled();
    }
}
