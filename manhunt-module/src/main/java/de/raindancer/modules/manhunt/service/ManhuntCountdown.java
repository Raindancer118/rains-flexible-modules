package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.service.ManhuntService.RunCountdown;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The seconds between {@code /manhunt start} and the hunt actually beginning: a line a second, and
 * everybody frozen where they stand until it reaches zero.
 *
 * <h2>Why this is a copy of {@code SpeedrunCountdown} rather than a call to it</h2>
 * That class is package-private inside {@code speedrun-module} and takes that module's own lobby's
 * live "released" set — it is the countdown of a speedrun lobby, not a countdown. What is worth
 * copying is the one trick in it: freezing by <em>block-quantised comparison</em> rather than
 * cancelling every {@link PlayerMoveEvent} outright, so a frozen player can still look around while
 * they wait. {@code HunterHoldListener} already copies the same three lines for the head start, with
 * the same note.
 *
 * <h2>Why frozen at all</h2>
 * A countdown a Runner can spend walking is not a countdown, it is a head start nobody configured —
 * and the head start is a separate setting with its own listener.
 */
public final class ManhuntCountdown implements Listener, RunCountdown {


    private final Plugin plugin;
    private final Messages messages;

    private Set<UUID> frozen = Set.of();

    public ManhuntCountdown(Plugin plugin, Messages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = messages;
    }

    /** The real, scheduling implementation — what {@code ManhuntService}'s public constructor uses. */
    public static RunCountdown viaScheduling(Plugin plugin, Messages messages) {
        return new ManhuntCountdown(plugin, messages);
    }

    @Override
    public void count(Set<UUID> participants, int seconds, Runnable onDone) {
        if (seconds <= 0) {
            onDone.run();
            return;
        }
        frozen = Set.copyOf(participants);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        int[] left = {seconds};
        announce(participants, left[0]);
        Scheduling.globalTimer(plugin, 20L, 20L, task -> {
            left[0]--;
            if (left[0] > 0) {
                announce(participants, left[0]);
                return;
            }
            task.cancel();
            release();
            say(participants, "manhunt.countdown.go");
            onDone.run();
        });
    }

    /** A line a second in chat — no boss bar, which was taken out of this module entirely. */
    private void announce(Set<UUID> participants, int left) {
        say(participants, "manhunt.countdown.tick", "seconds", String.valueOf(left));
    }

    private void say(Set<UUID> participants, String key, String... values) {
        if (messages == null) {
            return;
        }
        for (UUID id : participants) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                messages.send(player, key, (Object[]) values);
            }
        }
    }

    private void release() {
        HandlerList.unregisterAll(this);
        frozen = Set.of();
    }

    /** Blocks an actual step, not a look around — see {@code HunterHoldListener.onMove}, which this
     *  matches deliberately so the two freezes in this module behave identically. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    public String describe() {
        return "the countdown before a hunt, with everybody held where they stand";
    }
}
