package de.raindancer.modules.arena.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.ArenaSettings;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Fight;
import de.raindancer.modules.arena.model.Kit;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.Place;
import de.raindancer.modules.arena.rules.PairingRule;
import de.raindancer.modules.arena.rules.StakeRule;
import de.raindancer.modules.arena.store.StatsStore;
import de.raindancer.modules.arena.util.Places;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Running fights: from a group the queue made (or an agreed invite) to everybody back in the hub.
 *
 * <ol>
 *   <li>Stakes are held on the survival server first; somebody who cannot pay calls the fight off before anybody moves.</li>
 *   <li>For own gear, a copy of what each player carries on survival is fetched — a copy: nothing here ever goes back.</li>
 *   <li>Each round: everybody to their spawn, kit or copy on, healed, frozen through the countdown, then the fight.</li>
 *   <li>Beaten in a round is watching until the next (or the end); a round decides, a fight ends; stats, stakes, home.</li>
 * </ol>
 *
 * <p>The decisions are {@link Fight}'s; this only acts on them. Every fight's state is changed under its own lock, from
 * whichever thread an event came in on, and every player is touched on that player's own thread.
 */
public final class MatchService implements IArenaService {

    private static final LogChannel log = Log.of("arena");
    private static final long BETWEEN_ROUNDS_TICKS = 60L;
    private static final long HOME_AFTER_TICKS = 80L;

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final Effects effects;
    private Drama drama;
    private final ArenaService arena;
    private final KitService kits;
    private final StakeService stakes;
    private final ArenaLink link;
    private final StatsStore stats;
    private final StakeRule stakeRule = new StakeRule();
    private final Map<UUID, Fight> fights = new ConcurrentHashMap<>();
    private final Map<UUID, Fight> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Fight> byMob = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> spectators = new ConcurrentHashMap<>();
    private final Map<UUID, Long> roundStarted = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> countdowns = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, List<String>>> gear = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deaths = new ConcurrentHashMap<>();
    private volatile WaveService waves;
    private volatile PitSweeper sweeper;
    private volatile Runnable afterFight = () -> {
    };
    private volatile java.util.function.Consumer<Fight> finished = fight -> {
    };
    private volatile ArenaSettings settings;
    private ScheduledTask clock;

    public MatchService(Plugin plugin, Server server, Messages messages, Effects effects, ArenaService arena,
                        KitService kits, StakeService stakes, ArenaLink link, StatsStore stats,
                        ArenaSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.effects = effects;
        this.arena = arena;
        this.kits = kits;
        this.stakes = stakes;
        this.link = link;
        this.stats = stats;
        this.settings = settings;
    }

    @Override
    public void settings(ArenaSettings settings) {
        this.settings = settings;
    }

    /** Wired after construction: the waves need this service and this one the waves. */
    public void waves(WaveService waves) {
        this.waves = waves;
    }

    private volatile FloodService floods;
    private final de.raindancer.modules.arena.rules.NavalSpotsRule navalSpots = new de.raindancer.modules.arena.rules.NavalSpotsRule();

    /** Wired after construction: floods a pit for a naval battle and drains it after. */
    public void floods(FloodService floods) {
        this.floods = floods;
    }

    /** Wired after construction: clears a pit before each round and after each fight. */
    public void sweeper(PitSweeper sweeper) {
        this.sweeper = sweeper;
    }

    /** Told about every fight that ended with a result, after its result was said — for the rematch. */
    public void onFinished(java.util.function.Consumer<Fight> told) {
        this.finished = told;
    }

    /** Told whenever a fight ends and its pit is free — the queue starts the next. */
    public void afterFight(Runnable next) {
        this.afterFight = next;
    }

    public void start() {
        clock = Scheduling.globalTimer(plugin, 20L, 20L, task -> secondPassed());
    }

    public void stop() {
        if (clock != null) {
            clock.cancel();
        }
        for (Fight fight : List.copyOf(fights.values())) {
            synchronized (fight) {
                if (fight.state() != Fight.State.OVER) {
                    finish(fight, fight.timeUp());
                }
            }
        }
    }

    // ── asking ─────────────────────────────────────────────────────────────────────────────────────

    public boolean inFight(UUID player) {
        return byPlayer.containsKey(player);
    }

    public Optional<Fight> fightOf(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Optional<Fight> fightOfMob(UUID entity) {
        return Optional.ofNullable(byMob.get(entity));
    }

    public List<Fight> fights() {
        return List.copyOf(fights.values());
    }

    public Set<String> busyPits() {
        Set<String> busy = new HashSet<>();
        fights.values().forEach(fight -> busy.add(fight.pit().id()));
        if (floods != null) {
            // A pit with water still in it is not free, though its fight is over.
            busy.addAll(floods.busyPits());
        }
        return busy;
    }

    /** Whether these two may hurt each other: opponents in one running fight, a mob of a fight included. */
    public boolean opponents(UUID a, UUID b) {
        Fight fight = byPlayer.get(a);
        if (fight == null) {
            fight = byPlayer.get(b);
        }
        if (fight == null) {
            fight = byMob.get(a);
        }
        if (fight == null) {
            fight = byMob.get(b);
        }
        if (fight == null) {
            return false;
        }
        synchronized (fight) {
            return fight.opponents(a, b);
        }
    }

    /** Whether somebody is in a fight that is running right now — not frozen, not between rounds, not watching. */
    public boolean isFighting(UUID player) {
        Fight fight = byPlayer.get(player);
        if (fight == null) {
            return false;
        }
        synchronized (fight) {
            return fight.state() == Fight.State.FIGHTING && fight.isStanding(player);
        }
    }

    /**
     * Frozen on their spawn through a countdown. Never in a naval battle: holding a rider still fights the boat under
     * them, and the whole start stutters — and nobody can be hurt before the fight starts anyway.
     */
    public boolean isFrozen(UUID player) {
        Fight fight = byPlayer.get(player);
        return fight != null && countdowns.containsKey(fight.id()) && !fight.spec().mode().isRidden();
    }

    public boolean isHarmless(UUID player) {
        Fight fight = byPlayer.get(player);
        return fight != null && kits.get(fight.spec().kit()).map(Kit::harmless).orElse(false);
    }

    // ── starting ───────────────────────────────────────────────────────────────────────────────────

    /** A fight from a group of players, in a free pit. The pit counts as taken from this moment. */
    public void begin(PairingRule.Group group, Pit pit) {
        MatchSpec spec = group.spec();
        Fight fight = new Fight(UUID.randomUUID(), spec, pit, group.sides());
        fights.put(fight.id(), fight);
        fight.players().forEach(player -> byPlayer.put(player, fight));
        log.info("Fight {} ({}, kit {}) in pit {}: {}", fight.id(), spec.mode(), spec.kit(), pit.id(),
                names(fight.players()));

        CompletableFuture<Optional<String>> held = spec.stake() > 0
                ? stakes.hold(fight.id(), fight.players(), spec.stake())
                : CompletableFuture.completedFuture(Optional.empty());
        held.thenCompose(refused -> {
            if (refused.isPresent()) {
                return CompletableFuture.completedFuture(refused);
            }
            return spec.isOwnGear() ? fetchGear(fight) : CompletableFuture.completedFuture(Optional.<String>empty());
        }).whenComplete((refused, failure) -> Scheduling.global(plugin, () -> {
            if (failure != null || refused.isPresent()) {
                String why = failure != null ? "the survival server" : refused.get();
                tellAll(fight, "arena.fight.called-off", "who", why);
                if (spec.stake() > 0 && (failure != null || !why.equals("the survival server"))) {
                    stakes.refund(fight.id());
                }
                dissolve(fight);
                return;
            }
            prepareRound(fight);
        }));
    }

    /** A copy of each player's survival gear; empty when every copy came, or the name of whoever's did not. */
    private CompletableFuture<Optional<String>> fetchGear(Fight fight) {
        Map<UUID, List<String>> copies = new ConcurrentHashMap<>();
        gear.put(fight.id(), copies);
        List<CompletableFuture<Optional<String>>> asked = new ArrayList<>();
        for (UUID player : fight.players()) {
            asked.add(link.ask(ArenaProtocol.GEAR, Map.of("player", player.toString())).handle((answer, failure) -> {
                if (failure != null || !"true".equals(answer.get("ok"))) {
                    return Optional.of(name(player));
                }
                List<String> slots = new ArrayList<>();
                for (JsonElement slot : JsonParser.parseString(answer.getOrDefault("items", "[]")).getAsJsonArray()) {
                    slots.add(slot.getAsString());
                }
                copies.put(player, slots);
                return Optional.<String>empty();
            }));
        }
        return CompletableFuture.allOf(asked.toArray(CompletableFuture[]::new)).thenApply(done -> asked.stream()
                .map(CompletableFuture::join).filter(Optional::isPresent).map(Optional::get).findFirst());
    }

    private void prepareRound(Fight fight) {
        synchronized (fight) {
            if (fight.state() == Fight.State.OVER) {
                return;
            }
            if (sweeper != null) {
                // Whatever the last round left — or the last fight in this pit — is gone before anybody arrives.
                sweeper.sweep(fight.pit());
            }
            if (fight.spec().mode() == Mode.NAVAL && floods != null) {
                floods.plan(fight.pit()).thenAccept(basin -> Scheduling.global(plugin, () -> launch(fight, basin)));
                return;
            }
            if ((fight.spec().mode() == Mode.MOUNTED || fight.spec().mode() == Mode.HORSEBACK) && floods != null) {
                Ride mount = fight.spec().mode() == Mode.MOUNTED ? Ride.PIG : Ride.HORSE;
                floods.planFloor(fight.pit()).thenAccept(floor -> Scheduling.global(plugin, () -> mount(fight, floor, mount)));
                return;
            }
            List<Place> spawns = fight.pit().spawns();
            List<Place> starts = spawns;
            Box ring = fight.pit().floor();
            if (fight.spec().mode() == Mode.SUMO && ring != null) {
                // Inside the ring, either side of its middle: the pit's own spawns are far outside any sumo ring.
                Place a = spawns.getFirst();
                Place b = spawns.get(spawns.size() > 1 ? 1 : 0);
                starts = new de.raindancer.modules.arena.rules.SumoRule().starts(ring.centreX(), ring.centreZ(), a.x(), a.z(),
                        b.x(), b.z(), settings.sumoRadiusClamped()).stream()
                        .map(start -> new Place(a.world(), start.x(), a.y(), start.z(), start.yaw(), 0)).toList();
            }
            int side = 0;
            for (Set<UUID> members : fight.sides()) {
                Place base = starts.get(side % starts.size());
                int index = 0;
                for (UUID member : members) {
                    if (!fight.has(member)) {
                        continue;
                    }
                    Place spot = new Place(base.world(), base.x() + (index % 2) * 1.5 - 0.75 * Math.min(1, members.size() - 1),
                            base.y(), base.z() + (index / 2) * 1.5, base.yaw(), base.pitch());
                    index++;
                    Player player = server.getPlayer(member);
                    if (player == null) {
                        continue;
                    }
                    Places.location(server, spot).ifPresent(at -> Scheduling.entity(plugin, player, () -> equip(fight, player, at)));
                }
                side++;
            }
            countdowns.put(fight.id(), settings.countdownClamped());
        }
    }

    /**
     * A naval battle: everybody in a boat round a ring on the dry floor, then the flood as a scene, then the countdown.
     * A pit that cannot hold water calls the fight off with every stake back.
     */
    private void launch(Fight fight, Optional<de.raindancer.modules.arena.rules.BasinRule.Basin> planned) {
        synchronized (fight) {
            if (fight.state() == Fight.State.OVER) {
                return;
            }
            if (planned.isEmpty()) {
                callOff(fight);
                return;
            }
            de.raindancer.modules.arena.rules.BasinRule.Basin basin = planned.get();
            Pit pit = fight.pit();
            Place a = pit.spawns().getFirst();
            Place b = pit.spawns().get(pit.spawns().size() > 1 ? 1 : 0);
            List<UUID> boarding = fight.players().stream().filter(fight::has).toList();
            Box floor = pit.floor();
            List<de.raindancer.modules.arena.rules.NavalSpotsRule.Spot> spots = navalSpots.spots(boarding.size(),
                    a.x(), a.z(), b.x(), b.z(), new HashSet<>(basin.layers().getFirst().water()),
                    floor.minX(), floor.maxX(), floor.minZ(), floor.maxZ());
            if (spots.size() < boarding.size()) {
                callOff(fight);
                return;
            }
            for (int index = 0; index < boarding.size(); index++) {
                Player player = server.getPlayer(boarding.get(index));
                de.raindancer.modules.arena.rules.NavalSpotsRule.Spot spot = spots.get(index);
                if (player != null) {
                    Places.location(server, FloodService.spot(pit, spot.x(), spot.z(), spot.yaw())).ifPresent(at ->
                            Scheduling.entity(plugin, player, () -> equip(fight, player, at, true)));
                }
            }
            int surface = basin.layers().getLast().y();
            floods.rise(pit, basin, () -> audience(fight), y -> liftBoats(fight, y), () -> {
                synchronized (fight) {
                    if (fight.state() != Fight.State.OVER) {
                        reboard(fight, surface);
                        countdowns.put(fight.id(), settings.countdownClamped());
                    }
                }
            }, () -> callOff(fight));
        }
    }

    /**
     * A layer of the flood stands: every boat still below it is lifted onto it, rider and all. A boat the water
     * simply rose over would be under water — and the game throws a rider out of a sunken boat after three seconds.
     */
    private void liftBoats(Fight fight, int layerY) {
        for (UUID member : fight.players()) {
            Player player = server.getPlayer(member);
            if (player == null || !(player.getVehicle() instanceof org.bukkit.entity.Boat boat)) {
                continue;
            }
            Scheduling.entity(plugin, boat, () -> {
                Location at = boat.getLocation();
                if (at.getY() < layerY + 0.4) {
                    Location lifted = at.clone();
                    lifted.setY(layerY + 0.55);
                    boat.teleportAsync(lifted, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
                            io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                }
            });
        }
    }

    /** The water is up: whoever is not in a boat — thrown out, or never in one — gets one on the surface. */
    private void reboard(Fight fight, int surfaceY) {
        for (UUID member : fight.players()) {
            Player player = server.getPlayer(member);
            if (player == null || !fight.has(member) || player.getVehicle() instanceof org.bukkit.entity.Boat) {
                continue;
            }
            Scheduling.entity(plugin, player, () -> {
                Location at = player.getLocation().clone();
                at.setY(surfaceY + 0.55);
                player.teleportAsync(at).thenAccept(moved -> Scheduling.entity(plugin, player, () -> {
                    org.bukkit.entity.boat.OakBoat boat = player.getWorld().spawn(at, org.bukkit.entity.boat.OakBoat.class);
                    boat.addPassenger(player);
                }));
            });
        }
    }

    /** Mounted combat: everybody on a saddled pig round a ring on the floor, then the countdown. */
    private void mount(Fight fight, Optional<de.raindancer.modules.arena.rules.BasinRule.Basin> floorPlan, Ride mount) {
        synchronized (fight) {
            if (fight.state() == Fight.State.OVER) {
                return;
            }
            Pit pit = fight.pit();
            List<UUID> riders = fight.players().stream().filter(fight::has).toList();
            List<de.raindancer.modules.arena.rules.NavalSpotsRule.Spot> spots = floorPlan.map(plan -> {
                Place a = pit.spawns().getFirst();
                Place b = pit.spawns().get(pit.spawns().size() > 1 ? 1 : 0);
                Box floor = pit.floor();
                return navalSpots.spots(riders.size(), a.x(), a.z(), b.x(), b.z(),
                        new HashSet<>(plan.layers().getFirst().water()), floor.minX(), floor.maxX(), floor.minZ(), floor.maxZ());
            }).orElse(List.of());
            if (spots.size() < riders.size()) {
                callOff(fight);
                return;
            }
            for (int index = 0; index < riders.size(); index++) {
                Player player = server.getPlayer(riders.get(index));
                de.raindancer.modules.arena.rules.NavalSpotsRule.Spot spot = spots.get(index);
                if (player != null) {
                    Places.location(server, FloodService.spot(pit, spot.x(), spot.z(), spot.yaw())).ifPresent(at ->
                            Scheduling.entity(plugin, player, () -> equip(fight, player, at, mount)));
                }
            }
            countdowns.put(fight.id(), settings.countdownClamped());
        }
    }

    /** The sumo ring's edge in red dust, so everybody can see where out begins. */
    private void drawRing(Fight fight) {
        Box floor = fight.pit().floor();
        if (floor == null || fight.pit().spawns().isEmpty()) {
            return;
        }
        double radius = settings.sumoRadiusClamped();
        double y = fight.pit().spawns().getFirst().y() + 0.15;
        Places.location(server, new Place(floor.world(), floor.centreX(), y, floor.centreZ(), 0, 0)).ifPresent(centre ->
                Scheduling.region(plugin, centre, () -> {
                    org.bukkit.Particle.DustOptions red = new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.4f);
                    int points = (int) Math.max(24, radius * 10);
                    for (int index = 0; index < points; index++) {
                        double angle = 2 * Math.PI * index / points;
                        centre.getWorld().spawnParticle(org.bukkit.Particle.DUST, centre.getX() + Math.cos(angle) * radius,
                                y, centre.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, red);
                    }
                }));
    }

    private void callOff(Fight fight) {
        tellAll(fight, "arena.naval.no-water");
        if (fight.spec().stake() > 0) {
            stakes.refund(fight.id());
        }
        Set<UUID> home = new HashSet<>(fight.players());
        dissolve(fight);
        for (UUID member : home) {
            Player player = server.getPlayer(member);
            if (player != null) {
                Scheduling.entity(plugin, player, () -> {
                    HubState.apply(player, settings);
                    arena.toHub(player);
                    arena.fightOver(member);
                });
            }
        }
    }

    /** A mounted-combat pig's speed; a farm pig's is 0.25. */
    private static final double PIG_SPEED = 0.6;

    /** What a fighter starts the round sitting on. */
    private enum Ride { NONE, BOAT, PIG, HORSE }

    private void equip(Fight fight, Player player, Location spawn) {
        equip(fight, player, spawn, Ride.NONE);
    }

    private void equip(Fight fight, Player player, Location spawn, boolean boat) {
        equip(fight, player, spawn, boat ? Ride.BOAT : Ride.NONE);
    }

    private void equip(Fight fight, Player player, Location spawn, Ride ride) {
        HubState.apply(player, settings);
        player.setGameMode(GameMode.ADVENTURE);
        player.teleportAsync(spawn).thenAccept(moved -> Scheduling.entity(plugin, player, () -> {
            MatchSpec spec = fight.spec();
            if (spec.isOwnGear()) {
                List<String> copy = gear.getOrDefault(fight.id(), Map.of()).get(player.getUniqueId());
                kits.giveCopy(player, copy == null ? List.of() : copy);
            } else {
                kits.get(spec.kit()).ifPresent(kit -> kits.give(player, kit));
            }
            messages.send(player, "arena.fight.round", "round", fight.round(), "mode", spec.mode().title());
            if (ride == Ride.BOAT) {
                org.bukkit.entity.boat.OakBoat boat = spawn.getWorld().spawn(spawn, org.bukkit.entity.boat.OakBoat.class);
                boat.addPassenger(player);
            } else if (ride == Ride.PIG) {
                // The mount cannot be hurt: the fight is rider against rider, not a race to kill the pig.
                org.bukkit.entity.Pig pig = spawn.getWorld().spawn(spawn, org.bukkit.entity.Pig.class, made -> {
                    made.setSaddle(true);
                    made.setInvulnerable(true);
                    // Far faster than a farm pig (0.25): a joust needs a charge, and the carrot's boost still adds.
                    org.bukkit.attribute.AttributeInstance speed = made.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
                    if (speed != null) {
                        speed.setBaseValue(PIG_SPEED);
                    }
                    made.setAdult();
                    made.setRemoveWhenFarAway(false);
                });
                pig.addPassenger(player);
            } else if (ride == Ride.HORSE) {
                org.bukkit.entity.Horse horse = spawn.getWorld().spawn(spawn, org.bukkit.entity.Horse.class, made -> {
                    made.setTamed(true);
                    made.setOwner(player);
                    made.setAdult();
                    made.setInvulnerable(true);
                    made.setRemoveWhenFarAway(false);
                    made.getInventory().setSaddle(new org.bukkit.inventory.ItemStack(org.bukkit.Material.SADDLE));
                    made.getInventory().setArmor(new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_HORSE_ARMOR));
                    made.setJumpStrength(0.7);
                });
                horse.addPassenger(player);
            }
        }));
    }

    private void secondPassed() {
        for (Fight fight : List.copyOf(fights.values())) {
            synchronized (fight) {
                tick(fight);
            }
        }
    }

    private void tick(Fight fight) {
        if (fight.spec().mode() == Mode.SUMO && fight.state() != Fight.State.OVER) {
            drawRing(fight);
        }
        Integer counting = countdowns.get(fight.id());
        if (counting != null) {
            if (counting > 0) {
                titleAll(fight, Component.text(String.valueOf(counting)), Component.empty());
                fight.players().forEach(player -> effects.play(player, Cues.COUNTDOWN));
                countdowns.put(fight.id(), counting - 1);
                return;
            }
            countdowns.remove(fight.id());
            fight.begin();
            roundStarted.put(fight.id(), System.currentTimeMillis());
            titleAll(fight, messages.get("arena.fight.go"), Component.empty());
            fight.players().forEach(player -> effects.play(player, Cues.COUNTDOWN_DONE));
            if (fight.spec().mode() == Mode.WAVES && waves != null) {
                waves.nextWave(fight);
            }
            return;
        }
        if (fight.state() != Fight.State.FIGHTING || fight.spec().mode() == Mode.WAVES) {
            return;
        }
        long started = roundStarted.getOrDefault(fight.id(), System.currentTimeMillis());
        long left = settings.fightSeconds() - (System.currentTimeMillis() - started) / 1000;
        if (left == 30 || left == 10) {
            tellAll(fight, "arena.fight.time-left", "seconds", left);
        }
        if (left <= 0) {
            handle(fight, fight.timeUp());
        }
    }

    // ── what happens in a fight ────────────────────────────────────────────────────────────────────

    /** Somebody took a blow that would have killed them. */
    public void beaten(Player player, UUID by) {
        Fight fight = byPlayer.get(player.getUniqueId());
        if (fight == null) {
            return;
        }
        synchronized (fight) {
            deaths.merge(player.getUniqueId(), 1, Integer::sum);
            Optional<Fight.Outcome> outcome = fight.beaten(player.getUniqueId(), by);
            String killer = by == null ? null : server.getPlayer(by) != null ? server.getPlayer(by).getName() : null;
            tellAllOne(fight, killer == null ? "arena.fight.down" : "arena.fight.down-by", "player", player.getName(),
                    "by", killer == null ? "" : killer);
            drama().fall(player, messages.get("arena.fight.defeated-title"),
                    killer == null ? Component.empty() : messages.get("arena.fight.defeated-by", "by", killer),
                    audience(fight), outcome.isPresent());
            if (outcome.isEmpty()) {
                watch(fight, player);
                return;
            }
            handle(fight, outcome.get());
        }
    }

    /** Somebody left the fight — quit, went home, back to survival. */
    public void left(UUID player) {
        Fight fight = byPlayer.remove(player);
        if (fight == null) {
            return;
        }
        synchronized (fight) {
            fight.left(player).ifPresent(outcome -> handle(fight, outcome));
            String name = name(player);
            tellAll(fight, "arena.fight.left", "player", name);
        }
    }

    /** A mob of a wave died, by a player's hand or otherwise. */
    public void mobDied(UUID entity, UUID killer) {
        Fight fight = byMob.remove(entity);
        if (fight == null) {
            return;
        }
        synchronized (fight) {
            if (fight.mobGone(entity, killer)) {
                cleared(fight);
            }
        }
    }

    /** The wave is done: the run is won, or the next comes after a breath. The caller holds the lock. */
    private void cleared(Fight fight) {
        if (fight.lastWaveCleared()) {
            handle(fight, fight.wavesCleared());
            return;
        }
        tellAll(fight, "arena.waves.cleared", "wave", fight.wave());
        Scheduling.globalLater(plugin, 100L, () -> {
            synchronized (fight) {
                if (fight.state() == Fight.State.FIGHTING && waves != null) {
                    waves.nextWave(fight);
                }
            }
        });
    }

    /** The mobs of a new wave, so they count as the fight's. */
    public void mobsSpawned(Fight fight, Set<UUID> spawned) {
        synchronized (fight) {
            fight.waveStarted(spawned);
            spawned.forEach(mob -> byMob.put(mob, fight));
            titleAll(fight, messages.get("arena.waves.title", "wave", fight.wave()), Component.empty());
            if (spawned.isEmpty() && fight.state() == Fight.State.FIGHTING) {
                cleared(fight);
            }
        }
    }

    private void handle(Fight fight, Fight.Outcome outcome) {
        if (!outcome.fightOver()) {
            Set<UUID> winners = fight.sides().get(outcome.side());
            tellAll(fight, "arena.fight.round-won", "round", fight.round() - 0, "winners", names(winners),
                    "score", score(fight));
            Scheduling.globalLater(plugin, BETWEEN_ROUNDS_TICKS, () -> {
                synchronized (fight) {
                    if (fight.state() == Fight.State.BETWEEN_ROUNDS) {
                        fight.nextRound();
                        prepareRound(fight);
                    }
                }
            });
            return;
        }
        finish(fight, outcome);
    }

    private void finish(Fight fight, Fight.Outcome outcome) {
        countdowns.remove(fight.id());
        MatchSpec spec = fight.spec();
        List<UUID> winners = outcome.side() >= 0 ? List.copyOf(fight.sides().get(outcome.side())) : List.of();
        Set<UUID> everybody = new HashSet<>();
        fight.sides().forEach(everybody::addAll);

        if (spec.mode() == Mode.WAVES) {
            boolean cleared = outcome.side() == 0;
            int reached = cleared ? fight.wave() : Math.max(0, fight.wave() - 1);
            tellAllOne(fight, cleared ? "arena.waves.won" : "arena.waves.lost", "wave", reached,
                    "waves", fight.wave());
            if (cleared) {
                everybody.stream().map(server::getPlayer).filter(java.util.Objects::nonNull).forEach(winner ->
                        drama().triumph(winner, messages.get("arena.waves.victory-title"),
                                messages.get("arena.waves.victory-subtitle", "waves", fight.wave())));
            }
            for (UUID player : everybody) {
                record(player, cleared ? 1 : 0, cleared ? 0 : 1, 0, fight.kills(player), reached);
            }
        } else if (winners.isEmpty()) {
            tellAll(fight, "arena.fight.draw", "why", outcome.why());
            everybody.forEach(player -> record(player, 0, 0, 1, fight.kills(player), 0));
        } else {
            String winnerNames = names(new HashSet<>(winners));
            Component forWinners = messages.variant("arena.fight.you-won", "winners", winnerNames, "score", score(fight));
            Component forLosers = messages.variant("arena.fight.you-lost", "winners", winnerNames, "score", score(fight));
            Component forWatchers = messages.variant("arena.fight.won", "winners", winnerNames, "score", score(fight));
            for (Player member : audience(fight)) {
                UUID id = member.getUniqueId();
                member.sendMessage(winners.contains(id) ? forWinners : everybody.contains(id) ? forLosers : forWatchers);
            }
            winners.stream().map(server::getPlayer).filter(java.util.Objects::nonNull).forEach(winner ->
                    drama().triumph(winner, messages.get("arena.fight.victory-title"),
                            messages.get("arena.fight.victory-subtitle", "score", score(fight))));
            for (UUID player : everybody) {
                record(player, winners.contains(player) ? 1 : 0, winners.contains(player) ? 0 : 1, 0,
                        fight.kills(player), 0);
            }
            winners.forEach(player -> effects.play(player, Cues.REWARD));
        }

        if (spec.stake() > 0) {
            if (winners.isEmpty()) {
                stakes.refund(fight.id());
                tellAll(fight, "arena.fight.stakes-back");
            } else {
                Map<UUID, Long> payouts = stakeRule.payouts(spec.stake(), everybody.size(), winners, 0);
                stakes.pay(fight.id(), payouts);
                payouts.forEach((player, amount) -> {
                    Player online = server.getPlayer(player);
                    if (online != null) {
                        messages.send(online, "arena.fight.stakes-won", "amount", amount);
                    }
                });
            }
        }

        if (waves != null) {
            waves.clear(fight);
        }
        if (spec.mode() == Mode.NAVAL && floods != null) {
            Set<UUID> rained = new HashSet<>(fight.players());
            rained.addAll(spectators.getOrDefault(fight.id(), Set.of()));
            // After the winner's moment, before anybody is sent home: the whirlpool takes the water.
            Scheduling.globalLater(plugin, 40L, () -> floods.drain(fight.pit(), rained, () -> {
                if (sweeper != null) {
                    sweeper.sweep(fight.pit());
                }
                afterFight.run();
            }));
        }
        Set<UUID> home = new HashSet<>(fight.players());
        home.addAll(spectators.getOrDefault(fight.id(), Set.of()));
        Scheduling.globalLater(plugin, HOME_AFTER_TICKS, () -> {
            if (sweeper != null && !busyPits().contains(fight.pit().id())) {
                sweeper.sweep(fight.pit());
            }
            for (UUID member : home) {
                Player player = server.getPlayer(member);
                if (player != null && byPlayer.get(member) == null) {
                    Scheduling.entity(plugin, player, () -> {
                        HubState.apply(player, settings);
                        arena.toHub(player);
                        arena.fightOver(member);
                    });
                }
            }
        });
        dissolve(fight);
        try {
            finished.accept(fight);
        } catch (RuntimeException failed) {
            log.error(failed, "Offering the rematch failed; the fight itself ended normally.");
        }
    }

    /** The fight is gone: its players are free, its pit is free, and the queue may start the next. */
    private void dissolve(Fight fight) {
        fights.remove(fight.id());
        byPlayer.values().removeIf(each -> each == fight);
        byMob.values().removeIf(each -> each == fight);
        spectators.remove(fight.id());
        roundStarted.remove(fight.id());
        gear.remove(fight.id());
        countdowns.remove(fight.id());
        Scheduling.global(plugin, afterFight);
    }

    private void record(UUID player, int wins, int losses, int draws, int kills, int wave) {
        String name = name(player);
        int died = deaths.getOrDefault(player, 0);
        deaths.remove(player);
        Scheduling.async(plugin, () -> stats.add(player, name, wins, losses, draws, kills, died, wave));
    }

    // ── watching ───────────────────────────────────────────────────────────────────────────────────

    /** Beaten, but the fight goes on: they watch from above the pit until it is decided. */
    private void watch(Fight fight, Player player) {
        Scheduling.entity(plugin, player, () -> {
            player.getInventory().clear();
            player.setGameMode(GameMode.SPECTATOR);
            centre(fight.pit()).ifPresent(player::teleportAsync);
            messages.send(player, "arena.fight.watching");
        });
    }

    /** {@code /arena spectate <player>}: watch somebody's fight. */
    public boolean spectate(Player watcher, UUID fighter) {
        Fight fight = byPlayer.get(fighter);
        if (fight == null || byPlayer.containsKey(watcher.getUniqueId())) {
            return false;
        }
        spectators.computeIfAbsent(fight.id(), id -> ConcurrentHashMap.newKeySet()).add(watcher.getUniqueId());
        Scheduling.entity(plugin, watcher, () -> {
            watcher.getInventory().clear();
            watcher.setGameMode(GameMode.SPECTATOR);
            centre(fight.pit()).ifPresent(watcher::teleportAsync);
        });
        return true;
    }

    public void stopWatching(UUID watcher) {
        spectators.values().forEach(set -> set.remove(watcher));
    }

    private Optional<Location> centre(Pit pit) {
        Box floor = pit.floor();
        if (floor == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(server.getWorld(floor.world())).map(world ->
                new Location(world, floor.centreX(), floor.minY() + 8, floor.centreZ(), 0, 60));
    }

    // ── words ──────────────────────────────────────────────────────────────────────────────────────

    private Drama drama() {
        if (drama == null) {
            drama = new Drama(plugin, effects);
        }
        return drama;
    }

    /** Everybody in the fight and everybody watching it who is online. */
    private List<Player> audience(Fight fight) {
        Set<UUID> ids = new HashSet<>();
        fight.sides().forEach(ids::addAll);
        ids.addAll(spectators.getOrDefault(fight.id(), Set.of()));
        return ids.stream().map(server::getPlayer).filter(java.util.Objects::nonNull).toList();
    }

    /** One wording, picked once, the same for everybody in the fight and watching it. */
    private void tellAllOne(Fight fight, String key, Object... values) {
        Component line = messages.variant(key, values);
        audience(fight).forEach(player -> player.sendMessage(line));
    }

    private void tellAll(Fight fight, String key, Object... values) {
        Set<UUID> audience = new HashSet<>();
        fight.sides().forEach(audience::addAll);
        audience.addAll(spectators.getOrDefault(fight.id(), Set.of()));
        for (UUID member : audience) {
            Player player = server.getPlayer(member);
            if (player != null) {
                messages.send(player, key, values);
            }
        }
    }

    private void titleAll(Fight fight, Component title, Component subtitle) {
        Title shown = Title.title(title, subtitle,
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200)));
        for (UUID member : fight.players()) {
            Player player = server.getPlayer(member);
            if (player != null) {
                player.showTitle(shown);
            }
        }
    }

    private String score(Fight fight) {
        List<String> parts = new ArrayList<>();
        for (int side = 0; side < fight.sides().size(); side++) {
            parts.add(String.valueOf(fight.roundsWon(side)));
        }
        return String.join(" : ", parts);
    }

    private String names(Set<UUID> players) {
        List<String> names = new ArrayList<>();
        players.forEach(player -> names.add(name(player)));
        return String.join(", ", names);
    }

    private String name(UUID player) {
        Player online = server.getPlayer(player);
        if (online != null) {
            return online.getName();
        }
        String offline = server.getOfflinePlayer(player).getName();
        return offline == null ? player.toString().substring(0, 8) : offline;
    }

    /** For the commands: a short line per running fight. */
    public Map<String, String> summary() {
        Map<String, String> lines = new HashMap<>();
        for (Fight fight : fights.values()) {
            lines.put(fight.pit().id(), fight.spec().mode().title() + " · " + names(fight.players()));
        }
        return lines;
    }
}
