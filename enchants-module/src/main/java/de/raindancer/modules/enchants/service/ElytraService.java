package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.EnchantsSettings;
import de.raindancer.modules.enchants.model.BoostCharges;
import de.raindancer.modules.enchants.rules.ToolRules;
import de.raindancer.modules.enchants.store.ElytraStore;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Boost and Launch on an elytra. Boost: a click in mid-glide pulls like a rocket, only harder, for a charge
 * that comes back slowly. Launch: sneak and jump to be thrown up, the elytra opened at the top and the player
 * flung forward. Both run on the player's own thread, Launch's flight once a tick from {@link #tick}.
 */
public final class ElytraService {

    private static final int BOOST_TICKS = 30;
    private static final double LAUNCH_UP = 2.4;
    /** Ticks from the push up to the elytra opening: near the top of the climb. */
    private static final int RISE_TICKS = 16;
    /** Long enough to land on purpose; walls and the ground do not hurt until then. */
    private static final long SHIELD_MILLIS = 12_000;
    private static final long WATCH_MILLIS = 20_000;

    private static final class Flight {
        int boostTicks;
        int boostsThisFlight;
        int riseTicks;
        Location launchedFrom;
        long launchedAt;
        long shieldUntil;
    }

    private final ElytraStore store;
    private final Supplier<EnchantsSettings> settings;
    private final EnchantAdvancements advancements;
    private final Effects effects;
    private final Messages messages;
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();

    public ElytraService(ElytraStore store, Supplier<EnchantsSettings> settings, EnchantAdvancements advancements,
                         Effects effects, Messages messages) {
        this.store = store;
        this.settings = settings;
        this.advancements = advancements;
        this.effects = effects;
        this.messages = messages;
    }

    private static int level(Player player, String enchantment) {
        return CustomEnchantments.levelOn(player.getInventory().getChestplate(), enchantment);
    }

    private static boolean playing(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    /** A click in the air. @return true if it was taken as a boost */
    public boolean click(Player player) {
        int level = level(player, RainsEnchants.BOOST);
        if (level < 1 || !player.isGliding()) {
            return false;
        }
        EnchantsSettings config = settings.get();
        long recharge = config.boostRechargeSeconds() * 1000L;
        long now = System.currentTimeMillis();
        BoostCharges charges = store.charges(player, level);
        Flight flight = flights.computeIfAbsent(player.getUniqueId(), id -> new Flight());
        if (flight.boostTicks > 0) {
            return true;
        }
        if (!charges.take(now, level, recharge)) {
            store.save(player, charges);
            player.sendActionBar(messages.get("enchants.boost-empty", "time", seconds(charges.millisToNext(now))));
            effects.play(player.getUniqueId(), Cues.NO);
            return true;
        }
        store.save(player, charges);
        flight.boostTicks = BOOST_TICKS;
        flight.boostsThisFlight++;
        player.sendActionBar(messages.get("enchants.boost-charges", "left", String.valueOf(charges.charges()),
                "of", String.valueOf(level)));
        effects.play(player.getUniqueId(), Cues.ABILITY);
        player.getWorld().spawnParticle(Particle.SONIC_BOOM, player.getLocation(), 1);
        advancements.used(player.getUniqueId(), RainsEnchants.BOOST);
        if (level >= 2 && flight.boostsThisFlight >= level) {
            advancements.boostsEmptied(player.getUniqueId());
        }
        return true;
    }

    /** A jump. @return true if it set off a launch */
    public boolean jump(Player player) {
        int level = level(player, RainsEnchants.LAUNCH);
        if (level < 1 || !player.isSneaking() || !playing(player) || player.isInsideVehicle() || player.isGliding()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long ready = store.launchReady(player);
        // Launch III never waits — not even out the cooldown another elytra's launch left behind.
        boolean waits = ToolRules.launchCooldownMillis(level, settings.get().launchCooldownSeconds()) > 0;
        if (waits && now < ready) {
            player.sendActionBar(messages.get("enchants.launch-cooldown", "time", seconds(ready - now)));
            effects.play(player.getUniqueId(), Cues.NO);
            return false;
        }
        store.launchReady(player, now + ToolRules.launchCooldownMillis(level, settings.get().launchCooldownSeconds()));
        Flight flight = flights.computeIfAbsent(player.getUniqueId(), id -> new Flight());
        // The push lands a tick after the jump: set during the jump, the client's own jump would replace it.
        flight.riseTicks = RISE_TICKS + 1;
        flight.launchedFrom = player.getLocation();
        flight.launchedAt = now;
        flight.shieldUntil = now + SHIELD_MILLIS;
        effects.play(player.getUniqueId(), Cues.ABILITY);
        advancements.used(player.getUniqueId(), RainsEnchants.LAUNCH);
        return true;
    }

    public void tick(Player player) {
        Flight flight = flights.get(player.getUniqueId());
        if (flight == null) {
            return;
        }
        if (flight.riseTicks > 0) {
            flight.riseTicks--;
            if (flight.riseTicks == RISE_TICKS) {
                player.setVelocity(new Vector(0, LAUNCH_UP, 0));
                player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 1);
            } else if (flight.riseTicks == 0) {
                fling(player);
            }
        }
        if (flight.boostTicks > 0) {
            if (!player.isGliding()) {
                flight.boostTicks = 0;
            } else {
                flight.boostTicks--;
                Vector look = player.getLocation().getDirection();
                Vector now = player.getVelocity();
                double[] next = ToolRules.boostStep(new double[]{now.getX(), now.getY(), now.getZ()},
                        new double[]{look.getX(), look.getY(), look.getZ()}, settings.get().boostSpeedPercent());
                player.setVelocity(new Vector(next[0], next[1], next[2]));
                player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(), 2, 0.1, 0.1, 0.1, 0.02);
            }
        }
        long now = System.currentTimeMillis();
        if (flight.launchedFrom != null) {
            Location here = player.getLocation();
            if (here.getWorld() == flight.launchedFrom.getWorld()) {
                advancements.launchCarried(player.getUniqueId(), here.distance(flight.launchedFrom));
            }
            if (now - flight.launchedAt > WATCH_MILLIS) {
                flight.launchedFrom = null;
            }
        }
        boolean down = !player.isGliding() && player.isOnGround();
        if (down) {
            flight.boostsThisFlight = 0;
        }
        if (down && flight.boostTicks == 0 && flight.riseTicks == 0 && flight.launchedFrom == null
                && now > flight.shieldUntil) {
            flights.remove(player.getUniqueId());
        }
    }

    private void fling(Player player) {
        if (player.getInventory().getChestplate() == null || level(player, RainsEnchants.LAUNCH) < 1) {
            return;
        }
        player.setGliding(true);
        Vector look = player.getLocation().getDirection().normalize();
        // Metres per second are blocks per second; velocity is per tick.
        player.setVelocity(look.multiply(settings.get().launchSpeed() / 20.0));
        player.getWorld().spawnParticle(Particle.SONIC_BOOM, player.getLocation(), 1);
    }

    /** Whether a wall or the ground should not hurt right now, because a launch threw the player. */
    public boolean shielded(Player player) {
        Flight flight = flights.get(player.getUniqueId());
        return flight != null && System.currentTimeMillis() < flight.shieldUntil;
    }

    public void forget(UUID player) {
        flights.remove(player);
    }

    private static String seconds(long millis) {
        long total = (millis + 999) / 1000;
        return total >= 60 ? total / 60 + "m " + total % 60 + "s" : total + "s";
    }
}
