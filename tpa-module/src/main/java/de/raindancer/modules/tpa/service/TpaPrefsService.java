package de.raindancer.modules.tpa.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.model.TpaPrefs;
import de.raindancer.modules.tpa.model.TpaRequest;
import de.raindancer.modules.tpa.store.TpaPrefsFile;
import de.raindancer.modules.tpa.store.TpaRequests;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Being left alone: the blanket switch and the block list.
 *
 * <h2>Why blocking somebody also takes back their request</h2>
 * Otherwise the block appears to have done nothing: the request they made a moment ago is still sitting
 * there waiting to be accepted, and the person who blocked them has to refuse it by hand before the
 * thing they just asked for takes effect.
 */
public final class TpaPrefsService implements ITpaService {

    private final TpaPrefsFile prefs;
    private final TpaRequests requests;
    private final Messages messages;

    private volatile TpaSettings settings;

    public TpaPrefsService(TpaPrefsFile prefs, TpaRequests requests, Messages messages,
                           TpaSettings settings) {
        this.prefs = prefs;
        this.requests = requests;
        this.messages = messages;
        this.settings = settings;
    }

    /**
     * Nothing here reads the settings yet.
     *
     * <p>Taken anyway, and said out loud rather than left off: the service that is forgotten when it
     * <em>starts</em> reading something is the one that keeps yesterday's numbers until the next
     * restart.
     */
    @Override
    public void settings(TpaSettings fresh) {
        this.settings = fresh;
    }

    /** What this player has decided. */
    public TpaPrefs of(UUID who) {
        return prefs.of(who);
    }

    /** Whether this person may ask that one, ignoring everything else. */
    public boolean mayBeAskedBy(UUID asked, UUID asker) {
        return prefs.of(asked).mayBeAskedBy(asker);
    }

    // ------------------------------------------------------------------------ the blanket switch

    /** Flips it, and says which way it went. */
    public boolean toggle(Player who) {
        TpaPrefs now = prefs.of(who.getUniqueId());
        return set(who, !now.accepting());
    }

    /** Sets it outright — what {@code /tptoggle on} means. */
    public boolean set(Player who, boolean accepting) {
        TpaPrefs now = prefs.of(who.getUniqueId());
        TpaPrefs next = accepting ? now.acceptingEverybody() : now.refusingEverybody();
        prefs.set(who.getUniqueId(), who.getName(), next);
        messages.send(who, accepting ? "tpa.now-accepting" : "tpa.now-refusing");
        return accepting;
    }

    // ------------------------------------------------------------------------ the block list

    /**
     * Blocks somebody, and takes back whatever they had asked.
     *
     * @return false when they were already blocked, which is worth saying rather than silently
     *         doing nothing
     */
    public boolean block(Player who, OfflinePlayer them) {
        TpaPrefs now = prefs.of(who.getUniqueId());
        if (now.hasBlocked(them.getUniqueId())) {
            messages.send(who, "tpa.already-blocked", "player", nameOf(them));
            return false;
        }
        prefs.seen(them.getUniqueId(), them.getName());
        prefs.set(who.getUniqueId(), who.getName(), now.blocking(them.getUniqueId()));

        // The request they made a moment ago would otherwise still be sitting there, waiting to be
        // accepted, and the block would look like it had done nothing.
        requests.from(them.getUniqueId())
                .filter(request -> request.to().equals(who.getUniqueId()))
                .ifPresent(request -> requests.withdraw(them.getUniqueId()));

        messages.send(who, "tpa.blocked", "player", nameOf(them));
        return true;
    }

    /** @return false when they were not blocked in the first place */
    public boolean unblock(Player who, OfflinePlayer them) {
        TpaPrefs now = prefs.of(who.getUniqueId());
        if (!now.hasBlocked(them.getUniqueId())) {
            messages.send(who, "tpa.not-blocked", "player", nameOf(them));
            return false;
        }
        prefs.set(who.getUniqueId(), who.getName(), now.unblocking(them.getUniqueId()));
        messages.send(who, "tpa.unblocked", "player", nameOf(them));
        return true;
    }

    /**
     * A name for somebody who may never have been seen.
     *
     * <p>Never {@code Bukkit.getOfflinePlayer(String)} — that blocks on a lookup against Mojang, from
     * what on Folia may be a region thread. The name is whatever was cached when they were last here.
     */
    public String nameOf(OfflinePlayer who) {
        if (who == null) {
            return "somebody";
        }
        String known = who.getName();
        if (known != null && !known.isBlank()) {
            return known;
        }
        return prefs.nameOf(who.getUniqueId()).orElse("somebody");
    }

    /** The same, from a uuid alone — what a block list of people who are offline shows. */
    public String nameOf(UUID who) {
        return prefs.nameOf(who).orElse("somebody");
    }

    /** Notes the name somebody currently has, so a block list can be read by a person. */
    public void seen(Player who) {
        prefs.seen(who.getUniqueId(), who.getName());
    }

    /** What somebody has asked, for a screen that offers to take it back. */
    public java.util.Optional<TpaRequest> outgoingOf(UUID who) {
        return requests.from(who);
    }

    @Override
    public String describe() {
        return "who is accepting requests, and who has blocked whom";
    }
}
