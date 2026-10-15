package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.core.ui.prompt.Question;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.EnchantsSettings;
import de.raindancer.modules.enchants.rules.Autopilot;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Autopilot: the player watches, as a spectator, a mannequin of themselves — their skin, their gear — launch, fly
 * across and land hard on the place they picked; then they are put down there and take over. Where they took off
 * and in which game mode is written on the player first, so a quit or a crash mid-flight hands them back at the
 * start, never stuck as a spectator.
 */
public final class AutopilotService {

    private static final NamespacedKey FROM = new NamespacedKey("rainsenchants", "autopilot_from");
    /** No flight as a way out of a fight: not this soon after a player's hit. */
    private static final long FIGHT_MILLIS = 15_000;
    private static final int LANDING_TICKS = 20;
    private static final int PREFETCH_TICKS = 24;
    private static final double CEILING_MARGIN = 12;

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<EnchantsSettings> settings;
    private final EnchantAdvancements advancements;
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> readyAt = new ConcurrentHashMap<>();

    private static final class Flight {
        final Player pilot;
        final GameMode mode;
        final Mannequin flier;
        final ItemDisplay camera;
        final Autopilot.Plan plan;
        final double[] heading;
        int tick;
        int landedFor = -1;
        volatile Autopilot.Camera view = Autopilot.Camera.CHASE;
        boolean left;
        boolean right;
        long ticketChunk = Long.MIN_VALUE;

        Flight(Player pilot, GameMode mode, Mannequin flier, ItemDisplay camera, Autopilot.Plan plan, double[] heading) {
            this.pilot = pilot;
            this.mode = mode;
            this.flier = flier;
            this.camera = camera;
            this.plan = plan;
            this.heading = heading;
        }
    }

    public AutopilotService(Plugin plugin, Messages messages, Supplier<EnchantsSettings> settings,
                            EnchantAdvancements advancements) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.advancements = advancements;
    }

    public boolean flying(UUID player) {
        return flights.containsKey(player);
    }

    /** A player hit this one: no autopilot for a while. */
    public void fought(UUID player) {
        lastFight.put(player, System.currentTimeMillis());
    }

    /** Whether the player may take off now; tells them why not. */
    public boolean ready(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA
                || CustomEnchantments.levelOn(chest, RainsEnchants.AUTOPILOT) < 1) {
            messages.send(player, "enchants.autopilot-no-elytra");
            return false;
        }
        if (flying(player.getUniqueId()) || player.getGameMode() == GameMode.SPECTATOR || player.isInsideVehicle()) {
            messages.send(player, "enchants.autopilot-busy");
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastFight.getOrDefault(player.getUniqueId(), 0L) < FIGHT_MILLIS) {
            messages.send(player, "enchants.autopilot-fighting");
            return false;
        }
        long ready = readyAt.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            messages.send(player, "enchants.autopilot-cooldown", "time", String.valueOf((ready - now + 999) / 1000));
            return false;
        }
        return true;
    }

    /** Asks another player whether the pilot may fly to them; flies to where they are once they say yes. */
    public void ask(Player pilot, Player target) {
        if (target.equals(pilot) || !target.isOnline()) {
            return;
        }
        messages.send(pilot, "enchants.autopilot-asked", "player", target.getName());
        UUID pilotId = pilot.getUniqueId();
        Question.asking(Parsers.yesNo())
                .owner("autopilot")
                .prompt(pilot.getName() + " wants to fly to you with Autopilot. Let them land beside you?")
                .suggest("yes", "no")
                .attempts(1)
                .onAnswer(yes -> {
                    Player asking = plugin.getServer().getPlayer(pilotId);
                    if (asking == null) {
                        return;
                    }
                    if (!yes) {
                        Scheduling.entity(plugin, asking, () -> messages.send(asking, "enchants.autopilot-refused",
                                "player", target.getName()));
                        return;
                    }
                    Location there = target.getLocation();
                    Scheduling.entity(plugin, asking, () -> {
                        if (asking.getWorld().equals(there.getWorld()) && ready(asking)) {
                            take(asking, there);
                        } else if (!asking.getWorld().equals(there.getWorld())) {
                            messages.send(asking, "enchants.autopilot-other-world");
                        }
                    });
                })
                .ask(target);
    }

    /** Takes off for {@code to}, in the pilot's own world. */
    public void take(Player pilot, Location to) {
        if (!ready(pilot) || !pilot.getWorld().equals(to.getWorld())) {
            return;
        }
        // Landing there is a teleport there: whatever may refuse one (a claim the pilot is banned from, say) is asked.
        org.bukkit.event.player.PlayerTeleportEvent asked = new org.bukkit.event.player.PlayerTeleportEvent(pilot,
                pilot.getLocation(), to.clone(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!asked.callEvent()) {
            messages.send(pilot, "enchants.autopilot-not-allowed");
            return;
        }
        World world = pilot.getWorld();
        Location from = pilot.getLocation();
        GameMode mode = pilot.getGameMode();
        pilot.getPersistentDataContainer().set(FROM, PersistentDataType.STRING, String.join("|", world.getName(),
                String.valueOf(from.getX()), String.valueOf(from.getY()), String.valueOf(from.getZ()),
                String.valueOf(from.getYaw()), String.valueOf(from.getPitch()), mode.name()));
        Autopilot.Plan plan = Autopilot.plan(new double[]{from.getX(), from.getY(), from.getZ()},
                new double[]{to.getX(), to.getY(), to.getZ()}, world.getMaxHeight() - CEILING_MARGIN);
        double[] heading = {to.getX() - from.getX(), to.getZ() - from.getZ()};
        Mannequin flier = world.spawn(from, Mannequin.class, made -> {
            made.setProfile(skinOf(pilot));
            made.setPersistent(false);
            made.setInvulnerable(true);
            made.setGravity(false);
            made.setImmovable(true);
            made.setSilent(true);
            made.customName(pilot.displayName());
            made.setCustomNameVisible(false);
            EntityEquipment gear = made.getEquipment();
            EntityEquipment own = pilot.getEquipment();
            gear.setHelmet(copy(own.getHelmet()));
            gear.setChestplate(copy(own.getChestplate()));
            gear.setLeggings(copy(own.getLeggings()));
            gear.setBoots(copy(own.getBoots()));
            gear.setItemInMainHand(copy(own.getItemInMainHand()));
            gear.setItemInOffHand(copy(own.getItemInOffHand()));
        });
        Autopilot.Point first = Autopilot.camera(plan.at(0), heading);
        ItemDisplay camera = world.spawn(at(world, first), ItemDisplay.class, made -> {
            made.setPersistent(false);
            made.setTeleportDuration(2);
        });
        Flight flight = new Flight(pilot, mode, flier, camera, plan, heading);
        flights.put(pilot.getUniqueId(), flight);
        pilot.setGameMode(GameMode.SPECTATOR);
        pilot.setSpectatorTarget(camera);
        messages.send(pilot, "enchants.autopilot-off");
        showCamera(pilot, flight.view);
        world.spawnParticle(Particle.SONIC_BOOM, from, 1);
        world.playSound(from, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.8f);
        world.playSound(from, Sound.ENTITY_BREEZE_WIND_BURST, 1f, 0.7f);
        Scheduling.entityTimer(plugin, flier, 1L, 1L, task -> {
            if (!step(flight)) {
                task.cancel();
            }
        }, () -> abort(flight));
    }

    /**
     * The pilot's skin as the server already has it. Without it (an offline-mode test server) the default one — never a
     * lookup at Mojang's, which is a wait and, when it times out, an error on the console.
     */
    private static ResolvableProfile skinOf(Player pilot) {
        var profile = pilot.getPlayerProfile();
        boolean textured = profile.getProperties().stream().anyMatch(property -> property.getName().equals("textures"));
        return textured ? ResolvableProfile.resolvableProfile().name(pilot.getName()).uuid(pilot.getUniqueId())
                .addProperties(profile.getProperties()).build() : Mannequin.defaultProfile();
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static Location at(World world, Autopilot.Point point) {
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    /** One tick of a flight. @return false once it is over */
    private boolean step(Flight flight) {
        Player pilot = flight.pilot;
        if (!pilot.isOnline() || !flight.flier.isValid() || pilot.getGameMode() != GameMode.SPECTATOR) {
            abort(flight);
            return false;
        }
        World world = flight.flier.getWorld();
        if (flight.landedFor >= 0) {
            if (++flight.landedFor >= LANDING_TICKS) {
                handOver(flight);
                return false;
            }
            return true;
        }
        int next = Math.min(flight.tick + 1, flight.plan.ticks());
        Autopilot.Point point = flight.plan.at(next);
        int chunkX = (int) Math.floor(point.x()) >> 4;
        int chunkZ = (int) Math.floor(point.z()) >> 4;
        Autopilot.Point ahead = flight.plan.at(next + PREFETCH_TICKS);
        world.getChunkAtAsync((int) Math.floor(ahead.x()) >> 4, (int) Math.floor(ahead.z()) >> 4);
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            // Waits for the land to load rather than flying into nothing.
            world.getChunkAtAsync(chunkX, chunkZ);
            return true;
        }
        long chunk = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
        if (chunk != flight.ticketChunk) {
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            release(world, flight.ticketChunk);
            flight.ticketChunk = chunk;
        }
        flight.tick = next;
        flight.flier.teleportAsync(at(world, point));
        // Gliding as a player on an elytra glides: the pose alone is drawn as swimming.
        boolean glide = point.phase() != Autopilot.Phase.LAUNCH || next >= 6;
        if (flight.flier.isGliding() != glide) {
            flight.flier.setGliding(glide);
        }
        Optional<Autopilot.Point> view = Autopilot.camera(point, flight.heading, flight.view);
        view.ifPresent(camera -> flight.camera.teleportAsync(at(world, camera)));
        org.bukkit.entity.Entity watched = view.isPresent() ? flight.camera : flight.flier;
        if (pilot.getSpectatorTarget() != watched) {
            pilot.setSpectatorTarget(watched);
        }
        if (next % 3 == 0) {
            world.spawnParticle(Particle.CLOUD, at(world, point), 2, 0.1, 0.1, 0.1, 0.01);
        }
        if (next >= flight.plan.ticks()) {
            Location landed = at(world, point);
            flight.flier.setGliding(false);
            pose(flight.flier, Pose.SNEAKING);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, landed, 1);
            world.spawnParticle(Particle.SONIC_BOOM, landed, 1);
            world.playSound(landed, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.7f);
            world.playSound(landed, Sound.ENTITY_PLAYER_ATTACK_CRIT, 2f, 0.5f);
            flight.landedFor = 0;
        }
        return true;
    }

    private static void pose(Mannequin flier, Pose pose) {
        try {
            if (flier.getPose() != pose) {
                flier.setPose(pose, true);
            }
        } catch (IllegalArgumentException notForAMannequin) {
            // A pose the mannequin cannot take: it keeps the one it has.
        }
    }

    private void release(World world, long chunk) {
        if (chunk != Long.MIN_VALUE) {
            world.removePluginChunkTicket((int) (chunk >> 32), (int) chunk, plugin);
        }
    }

    /** Landed: the player is put where the mannequin stands, in the mode they took off in. */
    private void handOver(Flight flight) {
        Player pilot = flight.pilot;
        Location landed = flight.flier.getLocation();
        landed.setPitch(0);
        end(flight);
        pilot.setSpectatorTarget(null);
        pilot.teleportAsync(landed).thenAccept(moved -> Scheduling.entity(plugin, pilot, () -> {
            if (!moved) {
                // Refused at the last moment: back to the start rather than left in the air.
                restore(pilot);
                return;
            }
            pilot.setGameMode(flight.mode);
            pilot.setFallDistance(0);
            pilot.getPersistentDataContainer().remove(FROM);
        }));
        ItemStack chest = pilot.getInventory().getChestplate();
        int level = chest == null ? 1 : CustomEnchantments.levelOn(chest, RainsEnchants.AUTOPILOT);
        readyAt.put(pilot.getUniqueId(), System.currentTimeMillis()
                + Autopilot.cooldownSeconds(level, settings.get().autopilotCooldownSeconds()) * 1000L);
        messages.send(pilot, "enchants.autopilot-landed");
        advancements.used(pilot.getUniqueId(), RainsEnchants.AUTOPILOT);
    }

    private void end(Flight flight) {
        flights.remove(flight.pilot.getUniqueId());
        release(flight.flier.getWorld(), flight.ticketChunk);
        flight.ticketChunk = Long.MIN_VALUE;
        flight.flier.remove();
        flight.camera.remove();
    }

    /** A key pressed in flight: left and right (A and D) switch between the cameras. */
    public void input(Player pilot, org.bukkit.Input input) {
        Flight flight = flights.get(pilot.getUniqueId());
        if (flight == null) {
            return;
        }
        boolean switched = false;
        if (input.isRight() && !flight.right) {
            flight.view = flight.view.next();
            switched = true;
        } else if (input.isLeft() && !flight.left) {
            flight.view = flight.view.previous();
            switched = true;
        }
        flight.left = input.isLeft();
        flight.right = input.isRight();
        if (switched) {
            showCamera(pilot, flight.view);
        }
    }

    private void showCamera(Player pilot, Autopilot.Camera view) {
        pilot.sendActionBar(messages.get("enchants.autopilot-camera", "camera", view.title()));
    }

    /** Cut short: the player goes back to where they took off. */
    private void abort(Flight flight) {
        if (!flights.containsKey(flight.pilot.getUniqueId())) {
            return;
        }
        end(flight);
        if (flight.pilot.isOnline()) {
            restore(flight.pilot);
        }
    }

    /** Leaving mid-flight: back at the start before the player is saved. */
    public void quit(Player player) {
        Flight flight = flights.get(player.getUniqueId());
        if (flight != null) {
            end(flight);
        }
        restore(player);
    }

    /** Joining with a flight still written on them (the server stopped mid-flight): back at the start. */
    public void join(Player player) {
        restore(player);
    }

    private void restore(Player player) {
        String from = player.getPersistentDataContainer().get(FROM, PersistentDataType.STRING);
        if (from == null) {
            return;
        }
        player.getPersistentDataContainer().remove(FROM);
        String[] part = from.split("\\|");
        Optional<World> world = Optional.ofNullable(plugin.getServer().getWorld(part[0]));
        GameMode mode = GameMode.SURVIVAL;
        try {
            mode = GameMode.valueOf(part[6]);
        } catch (RuntimeException unreadable) {
            // Survival is the safe guess.
        }
        player.setSpectatorTarget(null);
        if (world.isPresent()) {
            player.teleport(new Location(world.get(), Double.parseDouble(part[1]), Double.parseDouble(part[2]),
                    Double.parseDouble(part[3]), Float.parseFloat(part[4]), Float.parseFloat(part[5])));
        }
        player.setGameMode(mode);
        player.setFallDistance(0);
    }

    /** Every flight ended, as the module stops. */
    public void stopAll() {
        flights.values().forEach(this::abort);
    }
}
