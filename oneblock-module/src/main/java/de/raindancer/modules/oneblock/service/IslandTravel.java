package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Spawn;
import de.raindancer.modules.oneblock.rules.EntryRule;
import de.raindancer.modules.oneblock.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Going to an island: asking the entry rule, loading the world, finding where to land, and the move itself through
 * Core's {@link Travel}. Server thread.
 */
public final class IslandTravel implements IOneBlockService {

    private final Server server;
    private final IslandWorlds worlds;
    private final Travel travel;
    private final Messages messages;
    private final LongSupplier clock;
    private final EntryRule entry = new EntryRule();
    /** Guests let in by an accepted invite: player and island, for as long as they are on this server. */
    private final Set<String> guests = ConcurrentHashMap.newKeySet();
    private volatile OneBlockSettings settings;

    public IslandTravel(Server server, IslandWorlds worlds, Travel travel, Messages messages, LongSupplier clock,
                        OneBlockSettings settings) {
        this.server = server;
        this.worlds = worlds;
        this.travel = travel;
        this.messages = messages;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(OneBlockSettings fresh) {
        this.settings = fresh;
    }

    public IslandWorlds worlds() {
        return worlds;
    }

    public void letIn(UUID guest, Island island) {
        guests.add(guest + "@" + island.id());
    }

    public boolean invited(UUID player, Island island) {
        return guests.contains(player + "@" + island.id());
    }

    public void forget(UUID player) {
        guests.removeIf(entry -> entry.startsWith(player + "@"));
    }

    /** Whether they may go there — the refusal's key, or empty. */
    public Optional<String> refusal(Player player, Island island, IslandDimension dimension) {
        return entry.judge(island, dimension, player.getUniqueId(), invited(player.getUniqueId(), island),
                player.hasPermission(PermissionNodes.ADMIN), clock.getAsLong());
    }

    /** Sends them; a refusal is said to them. @return whether they are on the way */
    public boolean go(Player player, Island island, IslandDimension dimension) {
        Optional<String> refused = refusal(player, island, dimension);
        if (refused.isPresent()) {
            messages.send(player, refused.get(), "dimension", messages.get(dimension.nameKey()));
            return false;
        }
        Optional<Location> arrival = arrival(island, dimension);
        if (arrival.isEmpty()) {
            messages.send(player, "oneblock.go.failed");
            return false;
        }
        travel.go(player, arrival.get(), Trip.to("island").exactly(), new TravelWatcher() {
            @Override
            public void refused(Player traveller, TravelReason why, Trip trip) {
                messages.send(traveller, "oneblock.go.failed");
            }
        });
        return true;
    }

    /** Where somebody arrives: the team's spawn while there is ground under it, else on top of the block. */
    public Optional<Location> arrival(Island island, IslandDimension dimension) {
        Optional<World> world = worlds.load(island, dimension);
        if (world.isEmpty()) {
            return Optional.empty();
        }
        Spawn spawn = island.spawn(dimension);
        Location at = new Location(world.get(), spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        if (!at.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) {
            Spawn top = Spawn.above(island.block(dimension).orElseThrow().spot());
            at = new Location(world.get(), top.x(), top.y(), top.z(), spawn.yaw(), spawn.pitch());
        }
        return Optional.of(at);
    }

    /** Where somebody without an island waits. */
    public Location waiting() {
        World world = settings.waitingWorld().isEmpty() ? null : server.getWorld(settings.waitingWorld());
        if (world == null) {
            world = server.getWorlds().getFirst();
        }
        return world.getSpawnLocation().add(0.5, 0, 0.5);
    }

    public void toWaiting(Player player) {
        player.teleportAsync(waiting());
    }
}
