package de.raindancer.modules.worldutils.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.manage.WorldEntryRules;
import de.raindancer.core.world.manage.WorldFamily;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.worldutils.WorldUtilsSettings;
import de.raindancer.modules.worldutils.model.Dimension;
import de.raindancer.modules.worldutils.rules.LandingRule;
import de.raindancer.modules.worldutils.store.LastPositions;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Sending somebody to a world, or to another dimension of the world they are in.
 *
 * <h2>What is Core's</h2>
 * Whether they may go at all is {@link WorldEntryRules} — a locked End refuses here exactly as it
 * refuses a portal, whichever plugin locked it. Finding ground is {@link Safety}. The move itself is
 * {@link Travel}, so {@code /back} knows where they came from. What is left here is where to aim, and
 * what everybody is told.
 *
 * <h2>Who is told what</h2>
 * The person moved hears where they arrived; whoever typed the command hears only a refusal, since a
 * sender moving forty people with {@code @a} does not want forty lines saying it worked.
 */
public final class WorldTravelService implements IWorldUtilsService {

    private final Plugin plugin;
    private final Server server;
    private final Travel travel;
    private final Safety safety;
    private final WorldEntryRules entry;
    private final Messages messages;
    private final LastPositions lastPositions;
    private final LandingRule landing = new LandingRule();

    private volatile WorldUtilsSettings settings;

    public WorldTravelService(Plugin plugin, Server server, Travel travel, Safety safety,
                              WorldEntryRules entry, Messages messages, LastPositions lastPositions,
                              WorldUtilsSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.travel = travel;
        this.safety = safety;
        this.entry = entry;
        this.messages = messages;
        this.lastPositions = lastPositions;
        this.settings = settings;
    }

    @Override
    public void settings(WorldUtilsSettings fresh) {
        this.settings = fresh;
    }

    /** {@code /w}: where they last stood in {@code world}, or its spawn. */
    public void toWorld(CommandSender sender, Player who, World world) {
        if (who == null || world == null) {
            return;
        }
        if (world.equals(who.getWorld())) {
            messages.send(sender, "worldutils.already-there", "player", who.getName(), "world", world.getName());
            return;
        }
        WorldUtilsSettings now = settings;
        Location aim = now.rememberLastPosition()
                ? lastPositions.in(who.getUniqueId(), world.getName()).orElse(world.getSpawnLocation())
                : world.getSpawnLocation();
        send(sender, who, world, aim, false);
    }

    /** {@code /dim}: the matching place in another dimension of the world they are standing in. */
    public void toDimension(CommandSender sender, Player who, Dimension target) {
        if (who == null || target == null) {
            return;
        }
        World here = who.getWorld();
        Optional<Dimension> from = Dimension.of(here.getEnvironment());
        if (from.isPresent() && from.get() == target) {
            messages.send(sender, "worldutils.already-in-dimension",
                    "player", who.getName(), "dimension", target.label());
            return;
        }
        String wanted = WorldFamily.of(here.getName()).inDimension(target.environment()).orElse(null);
        World world = wanted == null ? null : server.getWorld(wanted);
        if (world == null) {
            messages.send(sender, "worldutils.no-such-dimension",
                    "dimension", target.label(), "world", wanted == null ? here.getName() : wanted);
            return;
        }
        Location standing = who.getLocation();
        Optional<LandingRule.Landing> aimed = from.flatMap(dimension -> landing.between(dimension,
                here.getCoordinateScale(), standing.getX(), standing.getY(), standing.getZ(),
                targetOf(target, world)));
        if (aimed.isEmpty()) {
            send(sender, who, world, LandingRule.arrivalPoint(who.getRespawnLocation(), world), false);
            return;
        }
        LandingRule.Landing at = aimed.get();
        send(sender, who, world, new Location(world, at.x(), at.y(), at.z(),
                standing.getYaw(), standing.getPitch()), at.surface());
    }

    private static LandingRule.Target targetOf(Dimension dimension, World world) {
        var border = world.getWorldBorder();
        return new LandingRule.Target(dimension, world.getCoordinateScale(), world.getMinHeight(),
                world.getMaxHeight(),
                border == null ? 0 : border.getCenter().getX(),
                border == null ? 0 : border.getCenter().getZ(),
                border == null ? 6.0E7 : border.getSize());
    }

    private void send(CommandSender sender, Player who, World world, Location aim, boolean surface) {
        Optional<Component> refused = entry.refusal(who, world);
        if (refused.isPresent()) {
            sender.sendMessage(refused.get());
            if (!sender.equals(who)) {
                who.sendMessage(refused.get());
            }
            return;
        }
        WorldUtilsSettings now = settings;
        Spot around = Travel.spotOf(aim);
        CompletableFuture<Optional<Spot>> ground;
        if (!now.safeArrival() || around == null) {
            ground = CompletableFuture.completedFuture(Optional.ofNullable(around));
        } else if (surface) {
            ground = safety.findSafeAtConsistentHeight(around, now.radius(), 2);
        } else {
            ground = safety.findSafe(around, now.radius());
        }
        ground.thenAccept(found -> Scheduling.entity(plugin, who, () -> {
            if (!who.isOnline()) {
                return;
            }
            if (found.isEmpty()) {
                messages.send(sender, "worldutils.nowhere-safe", "player", who.getName(), "world", world.getName());
                return;
            }
            Spot spot = found.get();
            if (world.getEnvironment() == World.Environment.NETHER && spot.y() >= world.getLogicalHeight()) {
                // On top of the bedrock roof: technically somewhere to stand, and exactly the place a
                // Nether arrival must never be. Better refused than stranded.
                messages.send(sender, "worldutils.nowhere-safe", "player", who.getName(), "world", world.getName());
                return;
            }
            Location exact = new Location(world, spot.centreX(), spot.y(), spot.centreZ(),
                    aim.getYaw(), aim.getPitch());
            // exactly(): the ground has already been found, here, with the search this destination
            // needed. Travel searching again would find the nearest pocket rather than the surface.
            travel.go(who, exact, Trip.to(world.getName()).exactly(), new Arriving(sender, world));
        })).exceptionally(failure -> {
            // Logged, not only reported: a search that threw looks exactly like one that found nothing
            // to the player, and that is what made a chunk-loading bug in Core look like a void End.
            Log.of("worldutils").warn(failure, "The ground search for {} in {} failed.", who.getName(), world.getName());
            messages.send(sender, "worldutils.nowhere-safe", "player", who.getName(), "world", world.getName());
            return null;
        });
    }

    /** What the traveller and the sender are told once Travel is done with them. */
    private final class Arriving implements TravelWatcher {

        private final CommandSender sender;
        private final World world;

        private Arriving(CommandSender sender, World world) {
            this.sender = sender;
            this.world = world;
        }

        @Override
        public void arrived(Player traveller, Location where, Trip trip) {
            messages.send(traveller, "worldutils.arrived", "world", world.getName());
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(sender, why == TravelReason.ALREADY_TRAVELLING
                            ? "worldutils.already-travelling" : "worldutils.travel-refused",
                    "player", traveller.getName(), "world", world.getName());
        }
    }

    @Override
    public String describe() {
        return "sending somebody to a world or a dimension, past whatever locks worlds";
    }
}
