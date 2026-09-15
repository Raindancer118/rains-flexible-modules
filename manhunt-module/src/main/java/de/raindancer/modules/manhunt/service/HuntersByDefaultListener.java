package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * On a server where the Runners are hand-picked, everybody else is a Hunter without having to say so.
 *
 * <h2>Why this exists when {@code Hunt.of} already treats non-Runners as Hunters</h2>
 * Because that only happens at the moment a hunt starts, and the question people ask is
 * <em>before</em> it: the sides page says "nobody on the side so far", {@code /manhunt status} says
 * the same, and somebody who has been told they are hunting sees no red team over their head. With
 * {@code runnerSelfJoin} off there is nothing they can press to fix that either — the Runner button
 * is gone and the Hunt button is the only thing on the page. So the answer is made true up front
 * rather than at the start whistle.
 *
 * <h2>What it never does</h2>
 * Touch somebody who is already on a side. An assigned Runner stays a Runner — that is the whole
 * point of the setting this hangs off — and a Hunter is already where this would put them. It also
 * does nothing at all while {@code runnerSelfJoin} is on: there, picking a side is the player's, and
 * quietly filing everybody under Hunters would take the choice away by making it look already made.
 */
public final class HuntersByDefaultListener implements Listener {

    private final Supplier<ManhuntSettings> settings;
    private final ManhuntTeams teams;
    private final BooleanSupplier huntRunning;

    public HuntersByDefaultListener(Supplier<ManhuntSettings> settings, ManhuntTeams teams,
                                    BooleanSupplier huntRunning) {
        this.settings = settings;
        this.teams = teams;
        this.huntRunning = huntRunning;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        fileUnderHunters(event.getPlayer().getUniqueId());
    }

    /**
     * Everybody already online, in one pass — for the module coming up, and for the moment a host
     * turns {@code runnerSelfJoin} off with a lobby full of people who never picked anything.
     */
    public void sweep(Server server) {
        if (server == null) {
            return;
        }
        for (Player player : server.getOnlinePlayers()) {
            fileUnderHunters(player.getUniqueId());
        }
    }

    /** @return whether this actually put them on the Hunter side — for the test, and for a log line */
    public boolean fileUnderHunters(UUID player) {
        if (settings.get().runnerSelfJoin() || huntRunning.getAsBoolean()) {
            return false;
        }
        if (teams.isRunner(player) || teams.isHunter(player)) {
            return false;
        }
        teams.joinHunters(player);
        return true;
    }
}
