package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Keeping the round inside the arena.
 *
 * <h2>What a portal does to a tournament</h2>
 * A tribute who reaches the End is a tribute the border cannot close on, the deathmatch cannot teleport, and
 * the winner rule has to keep counting as alive. The round then cannot end: everybody else is dead and one
 * person is standing on an island the arena does not reach. That is not a hypothetical — it is the reason the
 * source plugin blocked End portals outright.
 *
 * <p>The Nether is different and is handled differently, which is the interesting part of this class.
 *
 * <h2>Why the Nether is allowed, but only in the middle</h2>
 * A Nether portal is a legitimate part of the game: the arena's own border is mirrored into the Nether at
 * the usual eighths scale, so somebody who goes down is still inside the round. What is not legitimate is
 * building a portal at the far edge of the map and using the Nether as a shortcut across the arena — eight
 * blocks of Nether per overworld block makes it a teleport, and a tribute who crosses the map in ten seconds
 * has left the game everybody else is playing.
 *
 * <p>So portals work within {@code arena.nether-allow-radius} of the middle, where the cornucopia is and
 * where everything is contested anyway, and not outside it. The distance is measured in two dimensions:
 * digging down and building a portal at bedrock is not a way round it.
 *
 * <h2>Nothing is blocked before the arena exists</h2>
 * The module is on a server whose ordinary life continues between tournaments. Blocking portals whenever the
 * plugin is loaded would mean a survival server where nobody can reach the Nether because a Hunger Games
 * round happened last week.
 */
public final class PortalListener implements IHungerGamesListener {

    /** Told when a portal is refused, for the log — a blocked portal is a thing players report as a bug. */
    @FunctionalInterface
    public interface Note {
        void say(String message);
    }

    private final Supplier<GamePhase> phase;
    private final Supplier<Location> arenaCentre;
    private final Messages messages;
    private final Note note;

    private volatile HungerGamesSettings settings;

    public PortalListener(Supplier<GamePhase> phase, Supplier<Location> arenaCentre, Messages messages,
                          Note note, HungerGamesSettings settings) {
        this.phase = phase;
        this.arenaCentre = arenaCentre;
        this.messages = messages;
        this.note = note;
        this.settings = settings;
    }

    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered. Every decision is the player's current position against the arena's
        // current middle, both read at the moment the portal is entered.
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!arenaExists()) {
            // Between tournaments this is an ordinary server and portals are an ordinary part of it.
            return;
        }
        Player player = event.getPlayer();

        switch (event.getCause()) {
            case END_PORTAL, END_GATEWAY -> {
                if (settings.blockEndPortals()) {
                    event.setCancelled(true);
                    messages.send(player, "hungergames.portal-end-blocked");
                    note.say(player.getName() + " tried to reach the End during a round.");
                }
            }
            case NETHER_PORTAL -> {
                if (settings.blockNetherPortals() && isTooFarOut(player.getLocation())) {
                    event.setCancelled(true);
                    messages.send(player, "hungergames.portal-nether-blocked",
                            "radius", String.valueOf(settings.netherAllowRadius()));
                    note.say(player.getName() + " tried to use a Nether portal "
                            + (int) distanceFromCentre(player.getLocation()) + " blocks out, "
                            + "beyond the allowed " + settings.netherAllowRadius() + ".");
                }
            }
            default -> {
                // Any other cause — a command, another plugin's teleport dressed as a portal. Not this
                // listener's business, and cancelling it would break whatever asked for it.
            }
        }
    }

    /**
     * Whether there is a round for a portal to escape from.
     *
     * <p>Both halves matter: an arena has to have been built, and something has to be happening in it. A
     * phase of {@code FINISHED} is people milling around after a round, and holding them out of the Nether
     * then is a restriction with no game behind it.
     */
    boolean arenaExists() {
        Location centre = arenaCentre.get();
        if (centre == null || centre.getWorld() == null) {
            return false;
        }
        GamePhase now = phase.get();
        return now != GamePhase.NOT_INITIALIZED && now != GamePhase.FINISHED;
    }

    /**
     * Whether this position is outside the radius Nether portals are allowed in.
     *
     * <p>A location in another world is never too far out — the restriction is about the arena's own world,
     * and applying it everywhere would block portals in the survival world and in every farm world on the
     * server.
     */
    boolean isTooFarOut(Location where) {
        Location centre = arenaCentre.get();
        if (where == null || centre == null || !centre.getWorld().equals(where.getWorld())) {
            return false;
        }
        return distanceFromCentre(where) > settings.netherAllowRadius();
    }

    /**
     * Flat distance from the middle of the arena, ignoring height.
     *
     * <p>Two-dimensional on purpose. Digging to bedrock and building a portal there is the obvious way round
     * a check that measured in three dimensions, and it is the first thing anybody tries.
     */
    double distanceFromCentre(Location where) {
        Location centre = arenaCentre.get();
        if (where == null || centre == null) {
            return 0;
        }
        double dx = where.getX() - centre.getX();
        double dz = where.getZ() - centre.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String describe() {
        return "keeping the round inside the arena";
    }
}
