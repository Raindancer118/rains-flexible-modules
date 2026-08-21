package de.raindancer.modules.tpa.listener;

import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaRequest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Somebody arriving, leaving, or dying.
 *
 * <h2>Why the module has only this one listener</h2>
 * Because the three things that end a warm-up — walking off, being hurt, logging out mid-teleport — are
 * Core's {@code TravelListener}, and the module registers that rather than writing a second copy. The
 * old plugin had all of it here, identical to the homes plugin's copy.
 *
 * <p>What is left is what belongs to this module: a death is somewhere to come back to, a name is worth
 * remembering so a block list can be read by a person, and somebody who logs out has to have their
 * requests taken back — both the one they made and the ones made to them. Otherwise the people who asked
 * wait out a full minute for an answer from somebody who is not there.
 */
public final class TpaSessionListener implements ITpaListener {

    private final TpaServices services;

    public TpaSessionListener(TpaServices services) {
        this.services = services;
    }

    /**
     * Where somebody died is where {@code /back} should take them.
     *
     * <p>{@code MONITOR} and not {@code ignoreCancelled}: a death cannot be cancelled, and this only
     * notices. Core's {@code Returns} is what keeps a death outranking a later teleport, so being moved
     * afterwards does not lose the spot their things are lying on.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player who = event.getEntity();
        services.back().died(who, who.getLocation());
    }

    /** Their name, so a block list is readable by a person rather than a column of uuids. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        services.prefs().seen(event.getPlayer());
    }

    /**
     * {@code MONITOR}: this decides nothing about the quit, it only notices.
     *
     * <p>Both directions of their requests go, and the other side is told each time — a request that
     * vanishes without a word is one somebody goes on waiting to answer.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player leaving = event.getPlayer();
        for (TpaRequest ended : services.requests().forget(leaving.getUniqueId())) {
            UUID other = ended.from().equals(leaving.getUniqueId()) ? ended.to() : ended.from();
            Player stillHere = services.server().getPlayer(other);
            if (stillHere == null || !stillHere.isOnline()) {
                continue;
            }
            services.messages().send(stillHere, "tpa.other-left", "player", leaving.getName());
        }
        forget(leaving.getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.asking().leaves(player);
        services.back().leaves(player);
    }

    @Override
    public String describe() {
        return "somebody arriving, leaving, or dying";
    }
}
