package de.raindancer.modules.arenagate.service;

import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.arenagate.GateSettings;
import de.raindancer.modules.arenagate.rules.GoRule;
import de.raindancer.modules.arenagate.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Sending somebody to the arena server.
 *
 * <p>The stand-still is Core's travel warm-up, aimed at where they already stand: the same countdown, the same "you
 * moved" and "you were hurt" as every other way of leaving a place on this server, and at the end, instead of a
 * teleport that goes nowhere, the proxy takes them. Nothing they own leaves this server — the arena server has
 * inventories of its own.
 */
public final class GateService implements IGateService {

    private final Plugin plugin;
    private final Messages messages;
    private final Travel travel;
    private final GoRule rule = new GoRule();
    private final de.raindancer.core.platform.link.ServerLink link;
    private final java.util.Set<java.util.UUID> leaving = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile GateSettings settings;

    public GateService(Plugin plugin, Messages messages, Travel travel, GateSettings settings,
                       de.raindancer.core.platform.link.ServerLink link) {
        this.link = link;
        this.plugin = plugin;
        this.messages = messages;
        this.travel = travel;
        this.settings = settings;
    }

    @Override
    public void settings(GateSettings settings) {
        this.settings = settings;
    }

    public void go(Player player) {
        go(player, null);
    }

    /**
     * Off to the arena, and what for once there: the arena is told first (the queue, a mob run, an agreed fight), then
     * the countdown, then the proxy. Told nothing, they simply arrive in the hub.
     */
    public void go(Player player, java.util.Map<String, String> intent) {
        if (intent != null && (link == null || !link.isConnected())) {
            messages.send(player, "arenagate.unreachable");
            return;
        }
        countdown(player, intent);
    }

    /**
     * The arena is told what somebody wants only once the countdown is through, right before the proxy takes them —
     * a countdown called off leaves nothing waiting over there.
     */
    private void sendWith(Player player, java.util.Map<String, String> intent, GateSettings now) {
        if (intent == null) {
            send(player, now);
            return;
        }
        java.util.Map<String, String> data = new java.util.HashMap<>(intent);
        data.put("player", player.getUniqueId().toString());
        link.request(de.raindancer.modules.arena.api.ArenaProtocol.INTENT, data, java.time.Duration.ofSeconds(8))
                .whenComplete((answer, failure) -> de.raindancer.core.platform.util.Scheduling.entity(plugin, player, () -> {
                    if (failure != null || !"true".equals(answer.get("ok"))) {
                        messages.send(player, "arenagate.unreachable");
                    } else {
                        send(player, now);
                    }
                }));
    }

    private void countdown(Player player, java.util.Map<String, String> intent) {
        Optional<String> refusal = rule.refusal(player.hasPermission(PermissionNodes.USE),
                travel.isTravelling(player.getUniqueId()));
        if (refusal.isPresent()) {
            messages.send(player, refusal.get());
            return;
        }
        GateSettings now = settings;
        if (now.warmupClamped() == 0) {
            sendWith(player, intent, now);
            return;
        }
        Location here = player.getLocation();
        travel.go(player, here, Trip.to("the arena").after(now.warmupClamped()).exactly().quiet(),
                new TravelWatcher() {
                    @Override
                    public void counting(Player traveller, int secondsLeft, Trip trip) {
                        messages.send(traveller, "arenagate.counting", "seconds", secondsLeft);
                    }

                    @Override
                    public void arrived(Player traveller, Location where, Trip trip) {
                        sendWith(traveller, intent, now);
                    }

                    @Override
                    public void cancelled(Player traveller, TravelReason why, Trip trip) {
                        messages.send(traveller, why == TravelReason.HURT ? "arenagate.cancelled-hurt"
                                : "arenagate.cancelled-moved");
                    }

                    @Override
                    public void refused(Player traveller, TravelReason why, Trip trip) {
                        messages.send(traveller, "arenagate.unreachable");
                    }
                });
    }

    private void send(Player player, GateSettings now) {
        messages.send(player, "arenagate.sending");
        leaving.add(player.getUniqueId());
        ProxyTransfer.send(plugin, player, now.arenaOrDefault());
    }

    /** Whether this player is leaving for the arena — their quit is "went to the arena", not "left the game". */
    public boolean isLeavingForTheArena(java.util.UUID player) {
        return leaving.remove(player);
    }

    /** Straight to the proxy, no countdown — for somebody already agreed to a fight and waited for over there. */
    public void sendNow(Player player) {
        send(player, settings);
    }
}
