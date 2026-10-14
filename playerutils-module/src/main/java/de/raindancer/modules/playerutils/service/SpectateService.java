package de.raindancer.modules.playerutils.service;

import de.raindancer.core.moderation.players.Outcome;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.store.SpectateReturns;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching somebody through their own eyes, and being put back afterwards exactly where and as what you were.
 *
 * <p>The way back is written on the spectator before anything changes, so a crash, a kick or a relog mid-watch
 * still returns them on their next join instead of leaving a staff member stranded as a ghost in somebody's
 * base. Following survives the watched player changing worlds.
 */
public final class SpectateService implements IPlayerUtilsService {

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    /** Spectator → watched. Only for following; the way back lives on the spectator. */
    private final Map<UUID, UUID> watching = new ConcurrentHashMap<>();
    private volatile PlayerUtilsSettings settings;
    private volatile NetworkReach reach;

    public SpectateService(Plugin plugin, Server server, Messages messages, PlayerUtilsSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(PlayerUtilsSettings fresh) {
        this.settings = fresh;
    }

    /** Lets somebody on another server be watched; without it nobody is. Set once by the module. */
    public void reach(NetworkReach reach) {
        this.reach = reach;
    }

    public Outcome start(Player viewer, Player target) {
        if (SpectateReturns.of(viewer).isEmpty()) {
            SpectateReturns.remember(viewer, SpectateReturns.from(viewer, target.getUniqueId()));
        }
        return begin(viewer, target);
    }

    private Outcome begin(Player viewer, Player target) {
        watching.put(viewer.getUniqueId(), target.getUniqueId());
        viewer.setGameMode(GameMode.SPECTATOR);
        attach(viewer, target);
        messages.send(viewer, "playerutils.spectate.started", "player", PlayerTargets.shownName(target));
        return Outcome.DONE;
    }

    /** Goes to {@code target} and looks through their eyes. */
    public void attach(Player viewer, Player target) {
        Location there = target.getLocation();
        viewer.teleportAsync(there).thenAccept(arrived -> {
            if (arrived) {
                Scheduling.onOwner(plugin, viewer, () -> {
                    if (viewer.getGameMode() == GameMode.SPECTATOR && target.isOnline()) {
                        viewer.setSpectatorTarget(target);
                    }
                });
            }
        });
    }

    /**
     * Watches somebody on another server: the staff member goes to where they stand, and the watching starts when they
     * arrive. The way back is the place they left here, whatever server they have followed the target to.
     *
     * @return false, after saying why, when it could not be started
     */
    public CompletableFuture<Boolean> startAcross(Player viewer, NetworkReach.Remote target) {
        NetworkReach network = reach;
        String shown = target.name();
        if (network == null) {
            messages.send(viewer, "playerutils.not-online", "player", shown);
            return CompletableFuture.completedFuture(false);
        }
        Optional<SpectateReturns.Return> already = SpectateReturns.of(viewer);
        NetworkReach.Spot from = already.map(back -> new NetworkReach.Spot(back.world(), back.x(), back.y(),
                back.z(), back.yaw(), back.pitch())).orElseGet(() -> {
                    Location at = viewer.getLocation();
                    return new NetworkReach.Spot(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                            at.getYaw(), at.getPitch());
                });
        GameMode mode = already.map(SpectateReturns.Return::mode)
                .orElse(viewer.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : viewer.getGameMode());
        String returnServer = already.filter(back -> !back.server().isBlank()).map(SpectateReturns.Return::server)
                .orElse(network.self());
        return network.prepareSpectate(target, viewer.getUniqueId(), from, mode, returnServer).thenCompose(spot -> {
            if (spot.isEmpty()) {
                messages.send(viewer, "playerutils.spectate.elsewhere-failed", "player", shown, "server", target.server());
                return CompletableFuture.completedFuture(false);
            }
            messages.send(viewer, "playerutils.spectate.travelling", "player", shown, "server", target.server());
            return network.travel(viewer, target.server(), spot.get()).thenApply(moved -> {
                if (!moved) {
                    network.cancelSpectate(target, viewer.getUniqueId());
                    messages.send(viewer, "playerutils.spectate.elsewhere-failed", "player", shown,
                            "server", target.server());
                }
                return moved;
            });
        });
    }

    /**
     * Somebody arrived who was said to be coming to watch: put them with the target, as a spectator, the way back
     * being where they came from. Saying so, and leaving them where they landed, when the target has gone.
     */
    public void resume(Player viewer, NetworkReach.Coming coming) {
        Player target = server.getPlayer(coming.target());
        if (target == null || !target.isOnline()) {
            messages.send(viewer, "playerutils.spectate.target-gone");
            return;
        }
        NetworkReach.Spot from = coming.from();
        SpectateReturns.remember(viewer, new SpectateReturns.Return(from.world(), from.x(), from.y(), from.z(),
                from.yaw(), from.pitch(), coming.mode(), target.getUniqueId(), coming.fromServer()));
        begin(viewer, target);
    }

    /** Back where they were, as what they were. False when they were not watching anybody. */
    public boolean stop(Player viewer) {
        Optional<SpectateReturns.Return> back = SpectateReturns.of(viewer);
        watching.remove(viewer.getUniqueId());
        if (back.isEmpty()) {
            return false;
        }
        SpectateReturns.forget(viewer);
        viewer.setSpectatorTarget(null);
        NetworkReach network = reach;
        if (network != null && back.get().isElsewhere(network.self())) {
            goBackAcross(viewer, network, back.get());
            return true;
        }
        Location to = back.get().location().orElse(server.getWorlds().getFirst().getSpawnLocation());
        GameMode mode = back.get().mode();
        viewer.teleportAsync(to).thenRun(() -> Scheduling.onOwner(plugin, viewer, () -> viewer.setGameMode(mode)));
        messages.send(viewer, "playerutils.spectate.stopped");
        return true;
    }

    /** The way back leads to another server: back to normal here, then over there to the place they left. */
    private void goBackAcross(Player viewer, NetworkReach network, SpectateReturns.Return back) {
        viewer.setGameMode(back.mode());
        NetworkReach.Spot to = new NetworkReach.Spot(back.world(), back.x(), back.y(), back.z(), back.yaw(), back.pitch());
        network.travel(viewer, back.server(), to).thenAccept(moved -> {
            if (!moved) {
                messages.send(viewer, "playerutils.spectate.back-failed", "server", back.server());
            }
        });
        messages.send(viewer, "playerutils.spectate.stopped");
    }

    public boolean isSpectating(Player viewer) {
        return SpectateReturns.of(viewer).isPresent();
    }

    /** Who is watching {@code target}, for following them and for letting go when they leave. */
    public java.util.List<UUID> watchersOf(UUID target) {
        return watching.entrySet().stream().filter(entry -> entry.getValue().equals(target))
                .map(Map.Entry::getKey).toList();
    }

    public void forget(UUID player) {
        watching.remove(player);
    }

    @Override
    public String describe() {
        return "watching somebody, and being put back afterwards";
    }
}
