package de.raindancer.modules.speedrun;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.manage.WorldRegenerator;
import de.raindancer.core.world.manage.WorldSeed;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The run's three worlds as files: making them once, and deleting and remaking them on a reset.
 *
 * <p>Only the <em>how</em>. Whether a reset happens at all — the most dangerous decision in the
 * module, since a wrong one deletes a world people are playing in — is {@link SpeedrunLobby}'s, and
 * every path to {@link #regenerate} goes through its one {@code resetTheRun}.
 */
final class SpeedrunWorldReset {

    private static final LogChannel log = Log.of("speedrun");

    private final Plugin plugin;
    private final Supplier<String> worldName;
    private final WorldRegenerator fallback = new WorldRegenerator();
    /** Who was standing in the run's worlds when a reset began, and is owed a way back into the fresh
     *  lobby — see {@link #rememberWhoIsIn}. Cleared as they are sent. */
    private final Set<UUID> owedAWayBack = ConcurrentHashMap.newKeySet();

    SpeedrunWorldReset(Plugin plugin, Supplier<String> worldName) {
        this.plugin = plugin;
        this.worldName = worldName;
    }

    /**
     * Makes sure the configured world and both its dimensions exist — nothing else here ever creates
     * one; a start only checks whether the world is loaded. A server that never touched
     * {@code world-name} gets {@link SpeedrunSettings#DEFAULT_WORLD_NAME} for free this way, instead
     * of a lobby that silently does nothing until somebody makes that world by hand.
     *
     * <p>Never touches an already-loaded world, even the server's own primary one. It only warns
     * when the configured name is the primary world: every reset on it will fail, and a warning up
     * front is easier to notice than a log line during a race.
     */
    void ensureExists() {
        String name = worldName.get();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            if (WorldRegenerator.isPrimaryWorld(existing)) {
                log.warn("The speedrun world is set to '{}', this server's primary world. It can "
                        + "never be unloaded, so /speedrunreset and the automatic reset after a run "
                        + "will always fail on it — set world-name to a dedicated world instead.", name);
            }
        } else {
            regenerator().create(name);
        }
        // Minecraft only links dimensions for the primary level's own folder layout, never for a world
        // made at runtime, so without these a nether portal in the speedrun world drops the racer into
        // the server's nether. See SpeedrunPortalListener.
        SpeedrunWorlds worlds = SpeedrunWorlds.around(name);
        if (Bukkit.getWorld(worlds.nether()) == null) {
            regenerator().create(worlds.nether(), World.Environment.NETHER);
        }
        if (Bukkit.getWorld(worlds.theEnd()) == null) {
            regenerator().create(worlds.theEnd(), World.Environment.THE_END);
        }
    }

    /**
     * Notes everybody standing in the run's worlds, to be put back into the fresh lobby by
     * {@link #sendBackWhoeverIsOwed}. Read before the regeneration, which evacuates everybody to
     * wherever they entered the world from rather than to the lobby.
     */
    void rememberWhoIsIn() {
        owedAWayBack.clear();
        owedAWayBack.addAll(whoIsIn());
    }

    /**
     * Wipes all three worlds a run is played across — {@code target} and the {@code _nether} and
     * {@code _the_end} beside it — and runs {@code onBack} once the overworld is back.
     *
     * <p>Resetting only the overworld would leave a finished run's nether and end standing: the
     * chests looted, the portal already lit, the dragon already dead. A companion that is not loaded
     * is simply not part of the group.
     *
     * <p><b>One operation, not three.</b> This used to regenerate the overworld and then each
     * companion in turn, and whoever was still standing in the nether was then sent back to where they
     * had entered it from — the overworld that had just been deleted. The nether's reset died on that,
     * and the end's was never reached. Core's {@link WorldRegenerator#regenerateAll} moves every
     * occupant of every world out of the whole group, waits for all of them, and only then unloads
     * anything, so there is nobody left to strand.
     *
     * <p>Folia: unloading, deleting and recreating a world are global-region operations, and callers
     * reach this from whatever thread a command or a quit event ran on.
     */
    void regenerate(World target, Runnable onBack) {
        SpeedrunWorlds worlds = SpeedrunWorlds.around(worldName.get());
        List<World> group = new ArrayList<>();
        group.add(target);
        for (String companion : List.of(worlds.nether(), worlds.theEnd())) {
            World loaded = Bukkit.getWorld(companion);
            if (loaded != null) {
                group.add(loaded);
            }
        }
        Scheduling.global(plugin, () -> regenerator().regenerateAll(group, WorldSeed.random(), ok -> {
            if (!ok) {
                log.warn("Not every world of the run could be regenerated ({}); the server log says "
                        + "which, and that one still holds whatever the last run left in it.",
                        String.join(", ", group.stream().map(World::getName).toList()));
            }
            // Announced whenever the overworld itself came back, even if a companion did not: a lobby
            // that never says it is ready again is one nobody can start a run in, which is worse than
            // a used nether. A different World object is the proof it is a new one — an overworld
            // that refused to unload is still the old object.
            World now = Bukkit.getWorld(worldName.get());
            if (now != null && now != target) {
                onBack.run();
            }
        }));
    }

    /**
     * Puts everybody {@link #rememberWhoIsIn} noted back into the world the reset just remade. Their
     * arrival is what hands them the lobby items — {@code SpeedrunLobbyListener.onWorldChange} — and
     * the lobby's ready sweep catches anybody the teleport did not have to move.
     */
    void sendBackWhoeverIsOwed() {
        if (owedAWayBack.isEmpty()) {
            return;
        }
        World lobbyWorld = Bukkit.getWorld(worldName.get());
        Location spawn = lobbyWorld == null ? null : lobbyWorld.getSpawnLocation();
        for (UUID id : Set.copyOf(owedAWayBack)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && spawn != null) {
                // teleportAsync rather than a scheduler hop: Paper takes this request from any
                // thread, and this runs on whichever one finished remaking the world.
                player.teleportAsync(spawn);
            }
        }
        owedAWayBack.clear();
    }

    /** Everybody standing in any of the three worlds a run is played across. */
    Set<UUID> whoIsIn() {
        SpeedrunWorlds worlds = SpeedrunWorlds.around(worldName.get());
        Set<UUID> found = new HashSet<>();
        for (String name : List.of(worlds.overworld(), worlds.nether(), worlds.theEnd())) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                found.add(player.getUniqueId());
            }
        }
        return found;
    }

    /**
     * Core's regenerator when Core is running — the one that writes every seed into the seed history —
     * and a plain one otherwise, which is what a test without a server gets.
     */
    private WorldRegenerator regenerator() {
        return RainsCore.isAvailable() ? RainsCore.get().worldRegenerator() : fallback;
    }
}
