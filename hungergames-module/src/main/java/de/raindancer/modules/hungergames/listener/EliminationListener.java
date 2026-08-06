package de.raindancer.modules.hungergames.listener;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Turns a tribute's death into an elimination.
 *
 * <h2>What this class is and is not allowed to decide</h2>
 * It translates and nothing else. Whether an elimination actually happens is
 * {@link GameSession#eliminate} — it checks the phase, counts the kill, fires the events, and asks the
 * winner rule whether the round is over. That matters because the same elimination can arrive from three
 * places: a death here, a gamemaster's button, and the disconnect timer. If the cannon and the broadcast
 * hung off this listener, two of the three would be silent.
 *
 * <p>So this does exactly three things a death gives it that nothing else has: the victim, the killer, and
 * the place they fell.
 *
 * <h2>Why the death message is suppressed</h2>
 * Vanilla announces "Alice was slain by Bram" and the round announces the elimination with a tribute count
 * on it. Both is two lines saying the same thing, one of which does not know there is a tournament on. The
 * vanilla one goes, and only once {@code eliminate} has said the elimination was real — a death outside the
 * round, or of somebody not in it, keeps its ordinary message.
 *
 * <h2>The invariant this listener must not break</h2>
 * <b>Being eliminated and being forgotten are different things.</b> A tribute who disconnects stays ALIVE
 * until something eliminates them, which is why they can rejoin mid-round and still be in the game.
 * {@link #forget} therefore drops nothing but this listener's own cache — see {@code IHungerGamesListener},
 * and {@code PackageGrammarTest}, which fails the build if any {@code forget} body reaches for an
 * elimination.
 */
public final class EliminationListener implements IHungerGamesListener {

    /** Playing the effect where somebody fell. Injected, so the listener needs no server to be tested. */
    @FunctionalInterface
    public interface Spectacle {

        /**
         * @param killed whether somebody killed them, as against the border or a fall — the two deserve
         *               different effects, and the source used one for both
         */
        void at(Location where, boolean killed);
    }

    /** What a tribute is told when the death action removes them from the server. */
    public static final String ELIMINATED = "You have been eliminated from the Hunger Games.";

    /**
     * Removing somebody from the server, when the death action says to.
     *
     * <p>An interface rather than a call, because a ban belongs to whatever keeps the server's punishment
     * record — Core's {@code Punishments} where the moderation module is installed, and nothing at all
     * where it is not. See {@link #applyDeathAction}.
     */
    public interface Eviction {

        void kick(Player who, String because);

        /** @return whether the ban was actually recorded somewhere it can be seen and lifted */
        boolean ban(Player who, String because);
    }

    private final GameSession session;
    private final SpectatorService spectators;
    private final Spectacle spectacle;
    private final Eviction eviction;

    /** Told when a death happened outside the round, for the log. Never a route to eliminating anybody. */
    private final Consumer<String> note;

    private volatile HungerGamesSettings settings;

    public EliminationListener(GameSession session, SpectatorService spectators, Spectacle spectacle,
                               Eviction eviction, Consumer<String> note, HungerGamesSettings settings) {
        this.session = session;
        this.spectators = spectators;
        this.spectacle = spectacle;
        this.eviction = eviction;
        this.note = note;
        this.settings = settings;
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>What happens to somebody who dies is a setting a gamemaster may change between rounds, and one
     * captured at startup would keep yesterday's answer.
     */
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player. Every decision is read from the session at the moment of the
        // death, which is also why this listener cannot leak — and why saying so is worth more than an
        // empty method. It is emphatically NOT a place to eliminate anybody: see the class note.
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!session.isWhitelisted(victim.getUniqueId())) {
            // Somebody who is not in the tournament — a spectator caught by a mob, an admin testing. Their
            // death is an ordinary Minecraft death and keeps its ordinary message.
            return;
        }

        Player killerPlayer = victim.getKiller();
        UUID killer = killerPlayer == null ? null : killerPlayer.getUniqueId();

        // Taken before eliminate(), because putting somebody into spectator mode moves them: the location
        // read afterwards is where they were respawned, not where they fell. That was a real bug in the
        // version this replaces — the effect played at the arena's spawn point rather than at the kill.
        Location fell = victim.getLocation().clone().add(0, 1, 0);

        if (!session.eliminate(victim.getUniqueId(), killer)) {
            // The round is not running, or they were already out. Either way this is not an elimination
            // and must not look like one.
            note.accept(victim.getName() + " died, but was not eliminated — the round is "
                    + session.phase() + " or they were already out.");
            return;
        }

        // Only now. The session's own broadcast carries the tribute count and the killer; vanilla's line
        // knows nothing about the tournament, and both is two announcements of one death.
        event.deathMessage(null);

        spectacle.at(fell, killer != null);
        applyDeathAction(victim);
    }

    /**
     * What happens to the tribute.
     *
     * <p>Spectator by default, and the alternatives matter: a tribute left standing in the arena in
     * survival is one who can still be hit, and a server running the tournament as a one-off event may
     * genuinely want people off the server when they are out.
     *
     * <h2>Why BAN is handed out rather than done here</h2>
     * Banning somebody is not this listener's business. It is a punishment, it goes on a record, it has a
     * duration and a reason, and RainsCore's {@code moderation.punishment.Punishments} already owns all of
     * that — which is also why a ban handed out by the tournament survives the tournament being
     * uninstalled. A {@code victim.ban(...)} here would write a Bukkit ban entry that no staff screen on
     * the server can see or lift.
     *
     * <p>So it goes to {@link Eviction}, which the module wires to Core. A host that supplies none gets a
     * kick and a line in the log saying so, which is the honest degradation: somebody is off the server
     * either way, and the difference is whether it was recorded.
     */
    private void applyDeathAction(Player victim) {
        switch (settings.deathAction()) {
            case SPECTATOR -> spectators.makeSpectator(victim);
            case KICK -> eviction.kick(victim, ELIMINATED);
            case BAN -> {
                if (!eviction.ban(victim, ELIMINATED)) {
                    note.accept("Death action is BAN but nothing on this server records bans, so "
                            + victim.getName() + " was kicked instead. A ban nobody can see or lift is "
                            + "worse than a kick.");
                    eviction.kick(victim, ELIMINATED);
                }
            }
        }
    }

    @Override
    public String describe() {
        return "turning a tribute's death into an elimination";
    }
}
