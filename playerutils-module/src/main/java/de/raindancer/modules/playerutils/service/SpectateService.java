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

    public Outcome start(Player viewer, Player target) {
        if (SpectateReturns.of(viewer).isEmpty()) {
            SpectateReturns.remember(viewer, SpectateReturns.from(viewer, target.getUniqueId()));
        }
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

    /** Back where they were, as what they were. False when they were not watching anybody. */
    public boolean stop(Player viewer) {
        Optional<SpectateReturns.Return> back = SpectateReturns.of(viewer);
        watching.remove(viewer.getUniqueId());
        if (back.isEmpty()) {
            return false;
        }
        SpectateReturns.forget(viewer);
        viewer.setSpectatorTarget(null);
        Location to = back.get().location().orElse(server.getWorlds().getFirst().getSpawnLocation());
        GameMode mode = back.get().mode();
        viewer.teleportAsync(to).thenRun(() -> Scheduling.onOwner(plugin, viewer, () -> viewer.setGameMode(mode)));
        messages.send(viewer, "playerutils.spectate.stopped");
        return true;
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
