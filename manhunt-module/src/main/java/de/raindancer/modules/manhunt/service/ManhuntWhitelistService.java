package de.raindancer.modules.manhunt.service;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.Server;

import java.nio.file.Path;
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

    private static final String CLOSED_BY_HUNT = "closed-by-hunt";

    private final WhitelistGateway gateway;
    private final WhitelistVips vips;
    /**
     * Whether the door is shut because a hunt shut it — on disk, because a crash or a restart mid-hunt
     * would otherwise leave a server whitelisted for good with nothing remembering why.
     */
    private final YamlStore state;

    public ManhuntWhitelistService(Server server, WhitelistVips vips, Path stateFile) {
        this(new BukkitWhitelistGateway(Objects.requireNonNull(server, "server")), vips, stateFile);
    }

    /** For tests: a fake gateway that never touches a live server. */
    ManhuntWhitelistService(WhitelistGateway gateway, WhitelistVips vips, Path stateFile) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.vips = Objects.requireNonNull(vips, "vips");
        this.state = new YamlStore(Objects.requireNonNull(stateFile, "stateFile"));
    }

    /** The people every one of these operations spares — see {@link WhitelistVips}. */
    public WhitelistVips vips() {
        return vips;
    }

    /**
     * Anybody can join again — by hand. Existing whitelist entries are left exactly as they were, and
     * a hunt that had shut the door no longer opens it at its end: somebody has taken it over.
     */
    public void open() {
        rememberClosedByHunt(false);
        gateway.setWhitelistEnabled(false);
    }

    /**
     * Whoever is online right now is whitelisted, and the server is shut to anybody else — by hand,
     * so no hunt ending opens it again.
     *
     * @return how many players were newly added — for the confirmation a command or a menu shows
     */
    public int close() {
        rememberClosedByHunt(false);
        return shut();
    }

    /**
     * A hunt is starting: the door is shut if it was open, and it is remembered that a hunt shut it.
     * A server that runs whitelisted all the time is left alone, so its door is never thrown open by
     * a hunt ending.
     *
     * @return whether this shut it
     */
    public boolean closeForHunt() {
        if (isClosed()) {
            return false;
        }
        shut();
        rememberClosedByHunt(true);
        return true;
    }

    /**
     * A hunt has ended — or the module came up after one that never got to: the door is opened if,
     * and only if, a hunt shut it and nobody has touched it by hand since.
     *
     * @return whether this opened it
     */
    public boolean reopenAfterHunt() {
        if (!state.read().getBoolean(CLOSED_BY_HUNT, false)) {
            return false;
        }
        rememberClosedByHunt(false);
        gateway.setWhitelistEnabled(false);
        return true;
    }

    /**
     * Somebody brought into a running hunt — a latecomer an admin assigned. Whoever was online when
     * the door shut is on the list already; without this a latecomer who disconnects is shut out of
     * the hunt they are in.
     */
    public void admit(UUID id) {
        if (isClosed() && !gateway.isWhitelisted(id)) {
            gateway.setWhitelisted(id, true);
        }
    }

    private void rememberClosedByHunt(boolean closed) {
        if (closed || state.exists()) {
            state.write(yaml -> yaml.set(CLOSED_BY_HUNT, closed));
        }
    }

    private int shut() {
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
