package de.raindancer.modules.worldutils.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.manage.SeedHistory;
import de.raindancer.core.world.manage.WorldFamily;
import de.raindancer.core.world.manage.WorldRegenerator;
import de.raindancer.core.world.manage.WorldSeed;
import de.raindancer.modules.worldutils.WorldUtilsSettings;
import de.raindancer.modules.worldutils.model.Dimension;
import de.raindancer.modules.worldutils.model.ManagedWorld;
import de.raindancer.modules.worldutils.store.LastPositions;
import de.raindancer.modules.worldutils.store.ManagedWorldStore;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Making, resetting and deleting worlds.
 *
 * <h2>What is Core's</h2>
 * All of the dangerous part. {@link WorldRegenerator} moves everybody out of every world in a group and
 * waits for them before unloading anything, copies the world's generator and type, carries game rules,
 * difficulty and border across, refuses the server's own dimensions, and writes every seed into
 * {@link SeedHistory} before the folder that held it is gone. This service picks the worlds, remembers
 * which ones it made, and says what happened.
 *
 * <h2>Threads</h2>
 * Creating, unloading and deleting a world are global-region operations, so every one of them hops
 * there first — a command can arrive on a player's region thread on Folia.
 */
public final class WorldAdminService implements IWorldUtilsService {

    private final Plugin plugin;
    private final Server server;
    private final WorldRegenerator regenerator;
    private final SeedHistory history;
    private final ManagedWorldStore managed;
    private final LastPositions lastPositions;
    private final Messages messages;
    private final LogChannel log;

    public WorldAdminService(Plugin plugin, Server server, WorldRegenerator regenerator, SeedHistory history,
                             ManagedWorldStore managed, LastPositions lastPositions, Messages messages,
                             LogChannel log) {
        this.plugin = plugin;
        this.server = server;
        this.regenerator = regenerator;
        this.history = history;
        this.managed = managed;
        this.lastPositions = lastPositions;
        this.messages = messages;
        this.log = log;
    }

    @Override
    public void settings(WorldUtilsSettings settings) {
        // Nothing read from the settings: which worlds exist is state, not configuration.
    }

    /**
     * Loads every world this module made that is not loaded yet — Paper will not, after a restart.
     *
     * @return how many were loaded
     */
    public int loadManaged() {
        int loaded = 0;
        for (ManagedWorld world : managed.load()) {
            if (server.getWorld(world.name()) != null) {
                continue;
            }
            if (regenerator.load(world.name(), world.environment())) {
                loaded++;
            } else {
                log.warn("'{}' is in worlds.yml but could not be loaded.", world.name());
            }
        }
        return loaded;
    }

    /**
     * Makes a world — or, with {@code family}, the world and its {@code _nether} and {@code _the_end}, all
     * from one seed so they belong together.
     */
    public void create(CommandSender sender, String name, Dimension dimension, boolean family, WorldSeed seed) {
        if (!ManagedWorld.isValidName(name)) {
            messages.send(sender, "worldutils.invalid-name", "name", name == null ? "" : name);
            return;
        }
        WorldFamily names = WorldFamily.of(name);
        List<String> wanted = family ? names.members() : List.of(name);
        for (String each : wanted) {
            if (server.getWorld(each) != null || managed.isManaged(each)) {
                messages.send(sender, "worldutils.already-exists", "world", each);
                return;
            }
        }
        // One seed for the whole family, drawn here when none was given: three worlds generated from
        // three different seeds are not one world's dimensions.
        WorldSeed chosen = seed == null || seed.kind() != WorldSeed.Kind.FIXED
                ? (family ? WorldSeed.fixed(ThreadLocalRandom.current().nextLong()) : WorldSeed.random())
                : seed;
        Scheduling.global(plugin, () -> {
            List<String> made = new ArrayList<>();
            for (String each : wanted) {
                World.Environment environment = family
                        ? environmentInFamily(names, each)
                        : (dimension == null ? inferred(each) : dimension.environment());
                if (!regenerator.create(each, environment, chosen)) {
                    messages.send(sender, "worldutils.create-failed", "world", each);
                    continue;
                }
                managed.add(new ManagedWorld(each, environment));
                made.add(each);
                World world = server.getWorld(each);
                messages.send(sender, "worldutils.created", "world", each,
                        "seed", world == null ? "?" : String.valueOf(world.getSeed()));
            }
            if (made.isEmpty()) {
                log.warn("Nothing was created for '{}'.", name);
            }
        });
    }

    /**
     * Throws a world — or its whole family — away and makes it again.
     *
     * @param family whether the loaded {@code _nether} and {@code _the_end} of the same world go too
     */
    public void regenerate(CommandSender sender, World world, WorldSeed seed, boolean family) {
        List<World> group = groupOf(world, family);
        if (refusedUpFront(sender, group)) {
            return;
        }
        List<String> names = group.stream().map(World::getName).toList();
        messages.send(sender, "worldutils.regenerating", "worlds", String.join(", ", names),
                "seed", (seed == null ? WorldSeed.random() : seed).describe());
        Scheduling.global(plugin, () -> regenerator.regenerateAll(group, seed, ok -> {
            names.forEach(lastPositions::forgetWorld);
            if (ok) {
                World back = server.getWorld(names.getFirst());
                messages.send(sender, "worldutils.regenerated", "worlds", String.join(", ", names),
                        "seed", back == null ? "?" : String.valueOf(back.getSeed()));
            } else {
                messages.send(sender, "worldutils.regenerate-failed", "worlds", String.join(", ", names));
            }
        }));
    }

    /** Deletes a world — or its whole family — for good. Every seed is kept in the history. */
    public void delete(CommandSender sender, World world, boolean family) {
        List<World> group = groupOf(world, family);
        if (refusedUpFront(sender, group)) {
            return;
        }
        List<String> names = group.stream().map(World::getName).toList();
        Scheduling.global(plugin, () -> regenerator.deleteAll(group, ok -> {
            for (String name : names) {
                if (server.getWorld(name) == null) {
                    managed.remove(name);
                    lastPositions.forgetWorld(name);
                }
            }
            messages.send(sender, ok ? "worldutils.deleted" : "worldutils.delete-failed",
                    "worlds", String.join(", ", names));
        }));
    }

    /** Every distinct seed this world has had, newest first. */
    public List<SeedHistory.Entry> seeds(String world) {
        return history.seeds(world);
    }

    /** Whether this module made {@code world}. */
    public boolean isManaged(String world) {
        return managed.isManaged(world);
    }

    private List<World> groupOf(World world, boolean family) {
        if (world == null) {
            return List.of();
        }
        if (!family) {
            return List.of(world);
        }
        List<World> group = new ArrayList<>();
        for (String member : WorldFamily.of(world.getName()).members()) {
            World loaded = server.getWorld(member);
            if (loaded != null) {
                group.add(loaded);
            }
        }
        return group;
    }

    /**
     * Said to the sender before anything happens. Core refuses these too, but only into the log — the
     * person who typed the command deserves the reason on their own screen.
     */
    private boolean refusedUpFront(CommandSender sender, List<World> group) {
        if (group.isEmpty()) {
            return true;
        }
        for (World world : group) {
            if (WorldRegenerator.isPrimaryWorld(world) || WorldRegenerator.isServerDimension(world)) {
                messages.send(sender, "worldutils.server-world", "world", world.getName());
                return true;
            }
        }
        return false;
    }

    private static World.Environment environmentInFamily(WorldFamily family, String member) {
        if (member.equalsIgnoreCase(family.nether())) {
            return World.Environment.NETHER;
        }
        if (member.equalsIgnoreCase(family.theEnd())) {
            return World.Environment.THE_END;
        }
        return World.Environment.NORMAL;
    }

    /** A name ending in {@code _nether} or {@code _the_end} means that dimension, as Minecraft's own do. */
    private static World.Environment inferred(String name) {
        return environmentInFamily(WorldFamily.of(name), name);
    }

    @Override
    public String describe() {
        return "making, resetting and deleting worlds, through Core's regenerator";
    }
}
