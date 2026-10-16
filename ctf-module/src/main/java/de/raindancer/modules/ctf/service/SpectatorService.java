package de.raindancer.modules.ctf.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.ctf.CtfSettings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who watches a round, and the camera that follows the flag: a watcher with following on is carried along with a flag
 * carrier, the next one when that one scores or falls. Sneaking lets go (the game's own spectator controls).
 */
public final class SpectatorService implements ICtfService {

    private final Plugin plugin;
    private final Set<UUID> watching = ConcurrentHashMap.newKeySet();
    private final Set<UUID> following = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, UUID> attachedTo = new ConcurrentHashMap<>();
    private volatile CtfSettings settings;

    public SpectatorService(Plugin plugin, CtfSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public boolean isWatching(UUID player) {
        return watching.contains(player);
    }

    public Set<UUID> watchers() {
        return Set.copyOf(watching);
    }

    /** Starts watching from {@code at}. Their own thread. */
    public void watch(Player player, Location at) {
        watching.add(player.getUniqueId());
        if (settings.followFlag()) {
            following.add(player.getUniqueId());
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        player.teleportAsync(at);
    }

    public void stopWatching(UUID player) {
        watching.remove(player);
        following.remove(player);
        attachedTo.remove(player);
    }

    public boolean toggleFollow(UUID player) {
        if (following.remove(player)) {
            return false;
        }
        following.add(player);
        return true;
    }

    /** Once a second: everybody following is put on a carrier's shoulder, if there is one. */
    public void follow(Optional<UUID> carrier) {
        if (carrier.isEmpty()) {
            return;
        }
        Player target = Bukkit.getPlayer(carrier.get());
        if (target == null) {
            return;
        }
        for (UUID id : following) {
            Player watcher = Bukkit.getPlayer(id);
            if (watcher == null || watcher.getGameMode() != GameMode.SPECTATOR || watcher.isSneaking()) {
                continue;
            }
            Scheduling.entity(plugin, watcher, () -> {
                UUID was = attachedTo.get(id);
                if (was != null && was.equals(target.getUniqueId()) && watcher.getSpectatorTarget() == null) {
                    // They sneaked off this carrier: they want to look round for themselves.
                    following.remove(id);
                    attachedTo.remove(id);
                    return;
                }
                attachedTo.put(id, target.getUniqueId());
                if (watcher.getSpectatorTarget() == null || !watcher.getSpectatorTarget().equals(target)) {
                    if (!watcher.getWorld().equals(target.getWorld())
                            || watcher.getLocation().distanceSquared(target.getLocation()) > 64 * 64) {
                        watcher.teleportAsync(target.getLocation()).thenRun(() ->
                                Scheduling.entity(plugin, watcher, () -> watcher.setSpectatorTarget(target)));
                    } else {
                        watcher.setSpectatorTarget(target);
                    }
                }
            });
        }
    }

    public void forget(UUID player) {
        stopWatching(player);
    }

    public void clear() {
        watching.clear();
        following.clear();
        attachedTo.clear();
    }
}
