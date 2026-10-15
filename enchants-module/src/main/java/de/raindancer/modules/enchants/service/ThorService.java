package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.rules.HeatSeeking;
import de.raindancer.modules.enchants.rules.Thor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Thor. An axe or a mace with it is thrown like Mjölnir — a right click into the air — strikes lightning where it hits
 * and flies back. The weapon itself never leaves the inventory: what flies is its likeness, and the weapon waits on a
 * cooldown till it is back, so nothing is lost to a crash or a quit mid-flight. A trident with it calls a storm where it
 * lands. All damage is the thrower's, so the server's combat rules decide it like any other hit; the lightning is
 * only for show and sets nothing alight.
 */
public final class ThorService {

    private static final NamespacedKey HAMMER = new NamespacedKey("rainsenchants", "thor_hammer");
    private static final NamespacedKey STORMED = new NamespacedKey("rainsenchants", "thor_stormed");
    private static final double THROW_SPEED = 2.2;
    private static final int MAX_FLIGHT_TICKS = 40;
    private static final double RETURN_SPEED = 1.4;
    private static final int MAX_RETURN_TICKS = 80;
    private static final double BOLT_REACH = 1.5;
    private static final double BOLT_HEIGHT = 3.0;
    private static final int MAX_GROUND_SEARCH = 8;
    private static final int TICKS_BETWEEN_BOLTS = 3;

    private final Plugin plugin;
    private final EnchantAdvancements advancements;
    private final SplittableRandom random = new SplittableRandom();

    public ThorService(Plugin plugin, EnchantAdvancements advancements) {
        this.plugin = plugin;
        this.advancements = advancements;
    }

    private static boolean hammer(Material material) {
        String name = material.name();
        return material == Material.MACE || name.endsWith("_AXE") && !name.endsWith("_PICKAXE");
    }

    /** A right click into the air. @return true if it threw the hammer */
    public boolean throwHammer(Player thrower, ItemStack held) {
        if (held == null || !hammer(held.getType()) || thrower.hasCooldown(held.getType())) {
            return false;
        }
        int level = CustomEnchantments.levelOn(held, RainsEnchants.THOR);
        if (level < 1) {
            return false;
        }
        thrower.setCooldown(held.getType(), Thor.cooldownTicks(level));
        ItemStack likeness = held.clone();
        likeness.setAmount(1);
        Vector aim = thrower.getEyeLocation().getDirection();
        fly(thrower, likeness, level, aim);
        // Volley: more hammers fanned out to the sides, each striking and coming back like the first.
        int volley = CustomEnchantments.levelOn(held, RainsEnchants.VOLLEY);
        if (volley > 0) {
            for (double[] side : de.raindancer.modules.enchants.rules.Volley.directions(
                    new double[]{aim.getX(), aim.getY(), aim.getZ()}, volley)) {
                fly(thrower, likeness, level, new Vector(side[0], side[1], side[2]));
            }
            advancements.used(thrower.getUniqueId(), RainsEnchants.VOLLEY);
        }
        thrower.getWorld().playSound(thrower.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 0.6f);
        return true;
    }

    /** One hammer thrown along {@code direction}: it strikes the first creature in its way and flies back. */
    private void fly(Player thrower, ItemStack likeness, int level, Vector direction) {
        // Dressed before its launch is announced, so Heat-seeking on the hammer finds it in flight.
        Snowball flying = thrower.launchProjectile(Snowball.class, direction.clone().normalize().multiply(THROW_SPEED),
                made -> {
                    made.setItem(likeness);
                    made.getPersistentDataContainer().set(HAMMER, PersistentDataType.INTEGER, level);
                });
        int[] ticks = {0};
        Scheduling.entityTimer(plugin, flying, 1L, 1L, task -> {
            if (!flying.isValid()) {
                task.cancel();
                return;
            }
            // Its own look at what the next tick's path crosses: the game let a thrown snowball pass through a mob.
            Optional<LivingEntity> struck = struckOnTheWay(thrower, flying);
            if (struck.isPresent()) {
                Location at = struck.get().getLocation();
                flying.remove();
                task.cancel();
                strike(thrower, struck.get(), at, Thor.hammerDamage(level));
                advancements.used(thrower.getUniqueId(), RainsEnchants.THOR);
                comeBack(thrower, at, likeness);
                return;
            }
            if (++ticks[0] >= MAX_FLIGHT_TICKS) {
                // Hit nothing: it turns back from wherever it got to.
                Location from = flying.getLocation();
                flying.remove();
                task.cancel();
                comeBack(thrower, from, likeness);
            }
        });
    }

    /** The first creature (not the thrower) whose hitbox the hammer's path crosses before the next tick. */
    private static Optional<LivingEntity> struckOnTheWay(Player thrower, Snowball flying) {
        Vector from = flying.getLocation().toVector();
        Vector step = flying.getVelocity();
        double length = step.length();
        if (length < 1.0E-6) {
            return Optional.empty();
        }
        Vector direction = step.clone().normalize();
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity each : flying.getWorld().getNearbyLivingEntities(flying.getLocation(), length + 2)) {
            if (each.equals(thrower) || each.isDead() || each instanceof org.bukkit.entity.ArmorStand) {
                continue;
            }
            var hit = each.getBoundingBox().expand(0.25).rayTrace(from, direction, length);
            if (hit != null) {
                double distance = hit.getHitPosition().distance(from);
                if (distance < best) {
                    best = distance;
                    nearest = each;
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    /** Whatever a projectile hit. A hammer strikes and turns back; a trident calls its storm. */
    public void hit(Projectile projectile, Entity hitEntity, Location at) {
        if (!(projectile.getShooter() instanceof Player thrower)) {
            return;
        }
        Integer hammerLevel = projectile.getPersistentDataContainer().get(HAMMER, PersistentDataType.INTEGER);
        if (hammerLevel != null && projectile instanceof Snowball flying) {
            strike(thrower, hitEntity, at, Thor.hammerDamage(hammerLevel));
            comeBack(thrower, at, flying.getItem());
            if (hitEntity instanceof LivingEntity) {
                advancements.used(thrower.getUniqueId(), RainsEnchants.THOR);
            }
            return;
        }
        if (projectile instanceof Trident trident
                && !trident.getPersistentDataContainer().has(STORMED)) {
            int level = CustomEnchantments.levelOn(trident.getItemStack(), RainsEnchants.THOR);
            if (level >= 1) {
                trident.getPersistentDataContainer().set(STORMED, PersistentDataType.BYTE, (byte) 1);
                storm(thrower, at, level);
            }
        }
    }

    /** One bolt: lightning to look at, and the thrower's damage to whatever it struck. */
    private void strike(Player thrower, Entity hitEntity, Location at, double damage) {
        at.getWorld().strikeLightningEffect(at);
        if (hitEntity instanceof LivingEntity living && !living.equals(thrower)) {
            living.damage(damage, thrower);
        }
    }

    private void storm(Player thrower, Location center, int level) {
        World world = center.getWorld();
        int bolt = 0;
        for (double[] spot : Thor.boltSpots(random, level)) {
            Location above = center.clone().add(spot[0], 0, spot[1]);
            Scheduling.regionLater(plugin, above, 1L + (long) bolt++ * TICKS_BETWEEN_BOLTS, () -> {
                // Lightning comes down to the ground, wherever on a wall the trident struck.
                Location where = ground(above);
                world.strikeLightningEffect(where);
                for (LivingEntity near : world.getNearbyLivingEntities(where, BOLT_REACH, BOLT_HEIGHT, BOLT_REACH)) {
                    if (!near.equals(thrower)) {
                        near.damage(Thor.stormDamage(level), thrower);
                    }
                }
            });
        }
        advancements.used(thrower.getUniqueId(), RainsEnchants.THOR);
    }

    /** The top of the first solid block at or below {@code from}, a few blocks down at most; {@code from} if none. */
    private static Location ground(Location from) {
        Location at = from.clone();
        for (int down = 0; down < MAX_GROUND_SEARCH; down++) {
            if (at.getBlock().getType().isSolid()) {
                return at.getBlock().getLocation().add(0.5, 1, 0.5);
            }
            at.subtract(0, 1, 0);
        }
        return from;
    }

    /** The hammer's likeness flies back into the thrower's hand. */
    private void comeBack(Player thrower, Location from, ItemStack likeness) {
        World world = from.getWorld();
        Scheduling.region(plugin, from, () -> {
            ItemDisplay returning = world.spawn(from, ItemDisplay.class, made -> {
                made.setItemStack(likeness);
                made.setPersistent(false);
                made.setTeleportDuration(1);
            });
            double[] velocity = {0, 0, 0};
            int[] ticks = {0};
            Scheduling.entityTimer(plugin, returning, 1L, 1L, task -> {
                Location now = returning.getLocation();
                Location hand = thrower.getEyeLocation().subtract(0, 0.4, 0);
                if (++ticks[0] > MAX_RETURN_TICKS || !thrower.isOnline() || thrower.getWorld() != world
                        || now.distanceSquared(hand) < 2.25) {
                    returning.remove();
                    task.cancel();
                    if (thrower.isOnline()) {
                        Scheduling.entity(plugin, thrower, () -> thrower.getWorld().playSound(thrower.getLocation(),
                                Sound.ITEM_TRIDENT_RETURN, 1f, 0.8f));
                    }
                    return;
                }
                double[] next = HeatSeeking.steer(velocity, new double[]{now.getX(), now.getY(), now.getZ()},
                        new double[]{hand.getX(), hand.getY(), hand.getZ()}, 0.6, RETURN_SPEED);
                System.arraycopy(next, 0, velocity, 0, 3);
                returning.teleport(now.clone().add(new Vector(next[0], next[1], next[2])));
            });
        });
    }

    /** Endermen in the shard world, where it nearly always rains, are not hurt or scared off by the rain. */
    public static boolean spared(Entity entity, boolean inShardWorld) {
        return entity instanceof org.bukkit.entity.Enderman && Thor.sparesEnderman(inShardWorld, entity.isInWater());
    }
}
