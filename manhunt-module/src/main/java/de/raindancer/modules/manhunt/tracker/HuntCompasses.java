package de.raindancer.modules.manhunt.tracker;

import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.util.Threads;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The hunt's three compasses — tracking, team and structure — handled as one: armed, swept, refitted
 * and taken back together, so no path can move one and forget the other two.
 *
 * <h2>Refitting, rather than "give" and "take" at each call site</h2>
 * A side change, a latecomer, a login mid-hunt and the start itself all ask the same question of
 * each compass: is this player owed one now? Each service answers for its own item ({@code fit}),
 * on the player's own thread. A side change used to move only the tracking compass, which left a
 * Hunter carrying the Runners' structure compass and a latecomer without a team compass.
 *
 * <h2>Registered for the life of the module</h2>
 * Somebody offline when a hunt ends keeps its compasses in their saved inventory — two of them bound
 * and impossible to drop. {@link #onJoin} takes those back, and hands a player who rejoins a live
 * hunt whatever they are owed and missing.
 */
public final class HuntCompasses implements Listener {

    private final Plugin plugin;
    private final Supplier<Optional<Hunt>> liveHunt;
    private final TrackerCompassService tracker;
    private final TeamCompassService team;
    private final StructureCompassService structures;

    public HuntCompasses(Plugin plugin, Supplier<Optional<Hunt>> liveHunt, TrackerCompassService tracker,
                         TeamCompassService team, StructureCompassService structures) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.team = Objects.requireNonNull(team, "team");
        this.structures = Objects.requireNonNull(structures, "structures");
        tracker.teamCompass(team);
        tracker.afterSweep(hunt -> {
            team.sweep(hunt);
            structures.sweep(hunt);
        });
    }

    public TrackerCompassService tracker() {
        return tracker;
    }

    public TeamCompassService team() {
        return team;
    }

    public StructureCompassService structures() {
        return structures;
    }

    /** A hunt has started: nothing carries over, and everybody online in it is handed what they are owed. */
    public void armFor(Hunt hunt) {
        team.arm();
        structures.arm();
        tracker.arm();
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                refit(hunt, player);
            }
        }
    }

    /** The hunt is over: every compass back from everybody in it who is online. */
    public void disarm(Hunt hunt) {
        tracker.disarm(hunt);
        team.disarm(hunt);
        structures.disarm(hunt);
    }

    /** {@code player}'s compasses in line with the side they are on now, on their own thread. */
    public void refit(Hunt hunt, Player player) {
        Threads.entity(plugin, player, () -> fit(hunt, player));
    }

    private void fit(Hunt hunt, Player player) {
        tracker.fit(hunt, player);
        team.fit(hunt, player);
        structures.fit(hunt, player);
    }

    /** Every compass of this module off {@code player} — they have left the hunt. */
    public void takeAll(Player player) {
        tracker.takeFrom(player);
        team.takeFrom(player);
        structures.takeFrom(player);
    }

    /** Somebody gone from the hunt or the server: whatever they were following is forgotten. */
    public void forget(UUID player) {
        tracker.forget(player);
        team.forget(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt != null) {
            fit(hunt, player);
            return;
        }
        // No hunt: the two bound compasses are dead weight nobody can drop. The structure compass
        // stays — it can be thrown away, and a hunt resumed after a restart still knows a used one.
        tracker.takeBack(player);
        team.takeBack(player);
    }

    public String describe() {
        return "the hunt's three compasses, handed out and taken back together";
    }
}
