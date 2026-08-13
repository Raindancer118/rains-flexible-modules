package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.ArenaBuildService;
import de.raindancer.modules.hungergames.service.GameTimerService;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.service.TeamPresentationService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Putting somebody who joins into the state the round says they are in.
 *
 * <h2>The case this class exists for</h2>
 * Somebody rejoining mid-round. A tribute who disconnected is still <b>alive</b> — that is the invariant the
 * whole winner logic rests on, and it is deliberately not a grace: their name is still on the list, their
 * kills still count, and the round can still come down to them. So when they come back they have to find the
 * arena as they left it: the boss bar, the scoreboard, their team, and no spectator mode.
 *
 * <p>Getting that wrong in either direction is bad in a way nobody notices until it is too late. Put a living
 * tribute into spectator and they are out of a tournament they are still winning. Leave a dead one in
 * survival and there is a ghost walking round the arena hitting people.
 *
 * <h2>Three kinds of person can join during a round</h2>
 * <ul>
 *   <li><b>A tribute who is still alive.</b> Welcomed back, given the round's furniture, left in whatever
 *       game mode they had. Nothing about them is reset — a rejoin is not a respawn.</li>
 *   <li><b>A tribute who is out.</b> Made a spectator, told the round is over for them. Not kicked, even
 *       when the death action is KICK: they have already been dealt with once, and doing it again on every
 *       reconnect is a boot loop.</li>
 *   <li><b>Somebody who is not in the tournament at all.</b> Spectator, and told why — the alternative is
 *       a stranger walking into a fight to the death in survival mode.</li>
 * </ul>
 *
 * <h2>Why the display name is refreshed on every join</h2>
 * The UUID is the identity and the name is decoration, which is exactly why the name goes stale: somebody
 * who changes it appears in the announcements, the tribute list and the winner line under a name nobody
 * recognises. The refresh is one call and it is the only moment the new name is knowable.
 */
public final class ConnectionListener implements IHungerGamesListener {

    /** Told what happened, for the round log. */
    @FunctionalInterface
    public interface Note {
        void say(String message);
    }

    private final GameSession session;
    private final SpectatorService spectators;
    private final TeamPresentationService presentation;
    private final GameTimerService timer;
    private final Messages messages;
    private final Note note;
    private final ArenaBuildService arena;

    private volatile HungerGamesSettings settings;

    public ConnectionListener(GameSession session, SpectatorService spectators,
                              TeamPresentationService presentation, GameTimerService timer,
                              Messages messages, Note note, HungerGamesSettings settings,
                              ArenaBuildService arena) {
        this.session = session;
        this.spectators = spectators;
        this.presentation = presentation;
        this.timer = timer;
        this.messages = messages;
        this.note = note;
        this.settings = settings;
        this.arena = arena;
    }

    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is kept here: every decision is read from the session at the moment somebody joins. The
        // one thing that *would* have to be dropped — their view of the boss bar and scoreboard — is
        // dropped in onQuit, because that is when it stops existing rather than when this is called.
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Ahead of everything else, and unconditional on the phase: /allow and the tribute file both
        // register somebody who has never connected under a UUID derived from their name, and this is the
        // one moment the real one becomes knowable. Done before the whitelist check below reads uuid, or
        // this join finds nobody home under the name they were added by and is treated as a stranger's.
        UUID placeholder = de.raindancer.modules.hungergames.store.TributeRoster.derivedIdFor(player.getName());
        if (!placeholder.equals(uuid) && session.claimRealIdentity(placeholder, uuid, player.getName())) {
            note.say(player.getName() + " connected for the first time — their placeholder tribute entry "
                    + "now carries their real account.");
        }

        boolean inTheTournament = session.isWhitelisted(uuid);

        if (inTheTournament) {
            // The only moment a changed name is knowable. Without this the announcements, the tribute list
            // and the winner line all name somebody nobody recognises.
            session.updateName(uuid, player.getName());
            presentation.show(uuid);
        }

        GamePhase phase = session.phase();
        if (phase != GamePhase.RUNNING && phase != GamePhase.FINISHED) {
            // Nothing is running, so there is nothing to be put back into. The lobby and the start-up
            // sequence look after their own arrivals.
            return;
        }

        // The round's furniture, for late arrivals as much as for rejoins — a spectator with no boss bar
        // cannot tell how long is left, which is most of what a spectator is watching for.
        timer.addViewer(uuid);

        // Everything below forces a game mode or a spectator state — real mutations of somebody's
        // account, not just a message. Confined to the arena's own world: a round running here does not
        // give this listener any business deciding what a stranger joining an unrelated world should be
        // doing on their own server, and doing so anyway once cost a player their entire inventory on an
        // entirely different server. See ArenaBuildService#arenaWorld.
        if (!arena.arenaWorld().filter(player.getWorld()::equals).isPresent()) {
            return;
        }

        if (!inTheTournament) {
            player.setGameMode(GameMode.SPECTATOR);
            messages.send(player, "hungergames.joined-as-spectator");
            return;
        }

        if (session.participants().isAlive(uuid)) {
            // Deliberately nothing else. A rejoin is not a respawn: no game mode change, no teleport, no
            // inventory reset. Whatever they were carrying when they dropped is what they still have.
            messages.send(player, "hungergames.rejoined-alive");
            note.say(player.getName() + " rejoined the arena and is still in the game.");
        } else {
            // Out, and stays out. Not kicked even when the death action is KICK — they have been dealt
            // with once already, and repeating it on every reconnect is a boot loop with a person in it.
            spectators.makeSpectator(player);
            messages.send(player, "hungergames.rejoined-eliminated");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // The view goes, the tribute does not. Leaving them as a viewer is an entry per player who has
        // ever been on the server; eliminating them here would end tournaments by pulling a plug.
        timer.removeViewer(event.getPlayer().getUniqueId());
        presentation.hide(event.getPlayer().getUniqueId());
    }

    @Override
    public String describe() {
        return "putting somebody who joins into the state the round says they are in";
    }
}
