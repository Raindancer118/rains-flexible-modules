package de.raindancer.modules.xaeromap.service;

import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.rules.RefreshDueRule;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Collection;

/**
 * The clock the claim sync runs on.
 *
 * <p>Its own class rather than a lambda in the module, because "how often" is a decision with two
 * mistakes in it that are worth writing down and testing. A refresh with nobody listening still walks
 * every claim on the server, so it is skipped entirely — a server with no map mods installed anywhere
 * should cost nothing at all. And the clock is stamped after the work rather than before, so a refresh
 * that takes longer than the interval does not immediately queue another: the interval means "between
 * one and the next", not "however often the timer fires".
 */
public final class RefreshService implements IXaeroMapService {

    private final ClaimSyncService sync;
    private final RefreshDueRule due;

    private volatile XaeroMapSettings settings;
    private volatile Instant lastRefresh;

    public RefreshService(ClaimSyncService sync, RefreshDueRule due, XaeroMapSettings settings) {
        this.sync = sync;
        this.due = due;
        this.settings = settings;
    }

    @Override
    public void settings(XaeroMapSettings settings) {
        this.settings = settings;
    }

    /** One tick of the fixed short timer. Does nothing unless a refresh is actually due. */
    public void tick(Collection<? extends Player> online, Instant now) {
        XaeroMapSettings current = settings;
        if (!current.claims() || online == null || online.isEmpty()) {
            return;
        }
        if (sync.readyCount() == 0) {
            return;
        }
        if (!due.isDue(lastRefresh, now, current.refresh())) {
            return;
        }
        sync.refresh(online);
        lastRefresh = now;
    }

    /** When the last refresh happened, or {@code null} if none has. For the diagnostic. */
    public Instant lastRefresh() {
        return lastRefresh;
    }
}
