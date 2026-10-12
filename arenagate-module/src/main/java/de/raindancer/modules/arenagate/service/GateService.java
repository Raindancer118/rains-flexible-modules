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
    private volatile GateSettings settings;

    public GateService(Plugin plugin, Messages messages, Travel travel, GateSettings settings) {
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
        Optional<String> refusal = rule.refusal(player.hasPermission(PermissionNodes.USE),
                travel.isTravelling(player.getUniqueId()));
        if (refusal.isPresent()) {
            messages.send(player, refusal.get());
            return;
        }
        GateSettings now = settings;
        if (now.warmupClamped() == 0) {
            send(player, now);
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
                        send(traveller, now);
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
        ProxyTransfer.send(plugin, player, now.arenaOrDefault());
    }
}
