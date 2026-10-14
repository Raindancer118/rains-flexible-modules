package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Combat;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.rules.HeatSeeking;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heat-seeking: a shot from a bow, crossbow or trident with it locks onto the creature the shooter most likely aimed
 * at and flies into it, never one the shooter may not hurt. On the projectile's own thread, a tick at a time.
 */
public final class HeatSeekingService {

    /** Ticks a lock lasts at most: a target running away for longer is let go. */
    private static final int MAX_TICKS = 100;
    /** Where on the target a lock aims: a little above the middle, where a body is. */
    private static final double BODY = 0.6;

    private final Plugin plugin;
    private final Combat combat;
    private final Messages messages;
    private final Effects effects;
    private final EnchantAdvancements advancements;
    /** Projectile → who shot it at what, while it flies. */
    private final Map<UUID, UUID[]> locks = new ConcurrentHashMap<>();

    public HeatSeekingService(Plugin plugin, Combat combat, Messages messages, Effects effects,
                              EnchantAdvancements advancements) {
        this.plugin = plugin;
        this.combat = combat;
        this.messages = messages;
        this.effects = effects;
        this.advancements = advancements;
    }

    /** A projectile left a player's hands. */
    public void launched(Player shooter, Projectile projectile) {
        int level = CustomEnchantments.levelOn(weaponOf(shooter, projectile), RainsEnchants.HEAT_SEEKING);
        if (level < 1) {
            return;
        }
        Optional<LivingEntity> target = target(shooter, level);
        if (target.isEmpty()) {
            return;
        }
        LivingEntity locked = target.get();
        locks.put(projectile.getUniqueId(), new UUID[]{shooter.getUniqueId(), locked.getUniqueId()});
        double speed = projectile.getVelocity().length();
        projectile.setGravity(false);
        shooter.sendActionBar(messages.get("enchants.heat-seeking-locked", "target", name(locked)));
        shooter.playSound(shooter.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 2.0f);
        double turn = HeatSeeking.turn(level);
        int[] ticks = {0};
        Scheduling.entityTimer(plugin, projectile, 1L, 1L, task -> {
            if (++ticks[0] > MAX_TICKS || !projectile.isValid() || projectile instanceof AbstractArrow arrow && arrow.isInBlock()
                    || projectile instanceof org.bukkit.entity.Trident trident && trident.hasDealtDamage() || !locked.isValid() || locked.isDead()
                    || locked.getWorld() != projectile.getWorld()) {
                if (projectile.isValid()) {
                    projectile.setGravity(true);
                }
                locks.remove(projectile.getUniqueId());
                task.cancel();
                return;
            }
            Location from = projectile.getLocation();
            Location to = locked.getLocation().add(0, locked.getHeight() * BODY, 0);
            Vector now = projectile.getVelocity();
            double[] next = HeatSeeking.steer(new double[]{now.getX(), now.getY(), now.getZ()},
                    new double[]{from.getX(), from.getY(), from.getZ()}, new double[]{to.getX(), to.getY(), to.getZ()},
                    turn, speed);
            projectile.setVelocity(new Vector(next[0], next[1], next[2]));
            if (ticks[0] % 2 == 0) {
                projectile.getWorld().spawnParticle(Particle.SMALL_FLAME, from, 1, 0, 0, 0, 0);
            }
        });
    }

    /** A projectile hit something: a locked one that hit its target earns the shooter the advancement. */
    public void hit(Projectile projectile, Entity hit) {
        UUID[] lock = locks.remove(projectile.getUniqueId());
        if (lock != null && hit != null && hit.getUniqueId().equals(lock[1])) {
            advancements.used(lock[0], RainsEnchants.HEAT_SEEKING);
        }
    }

    private static ItemStack weaponOf(Player shooter, Projectile projectile) {
        if (projectile instanceof AbstractArrow arrow && arrow.getWeapon() != null && !arrow.getWeapon().isEmpty()) {
            return arrow.getWeapon();
        }
        // A crossbow's firework is no arrow and says nothing of what shot it: the crossbow in the shooter's hands did.
        for (ItemStack held : new ItemStack[]{shooter.getInventory().getItemInMainHand(),
                shooter.getInventory().getItemInOffHand()}) {
            if (held.getType() == Material.CROSSBOW || held.getType() == Material.BOW) {
                return held;
            }
        }
        return null;
    }

    /** The creature nearest the shooter's aim that they may hurt and can see. */
    private Optional<LivingEntity> target(Player shooter, int level) {
        double range = HeatSeeking.range(level);
        Location eye = shooter.getEyeLocation();
        Vector look = eye.getDirection();
        Map<UUID, LivingEntity> byId = new ConcurrentHashMap<>();
        List<HeatSeeking.Candidate> candidates = new ArrayList<>();
        for (LivingEntity each : shooter.getWorld().getNearbyLivingEntities(eye, range)) {
            if (!lockable(shooter, each)) {
                continue;
            }
            Location middle = each.getLocation().add(0, each.getHeight() * BODY, 0);
            byId.put(each.getUniqueId(), each);
            candidates.add(new HeatSeeking.Candidate(each.getUniqueId(), middle.getX(), middle.getY(), middle.getZ()));
        }
        return HeatSeeking.pick(new double[]{eye.getX(), eye.getY(), eye.getZ()},
                new double[]{look.getX(), look.getY(), look.getZ()}, candidates, HeatSeeking.cone(level), range)
                .map(byId::get);
    }

    private boolean lockable(Player shooter, LivingEntity each) {
        if (each.equals(shooter) || each instanceof ArmorStand || each.isDead()
                || each instanceof Tameable pet && shooter.getUniqueId().equals(pet.getOwnerUniqueId())) {
            return false;
        }
        String world = shooter.getWorld().getName();
        if (each instanceof Player player) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                    || !shooter.canSee(player)
                    || combat.judge(Attack.between(shooter.getUniqueId(), player.getUniqueId(), world)) != Verdict.ALLOWED) {
                return false;
            }
        } else if (combat.judge(Attack.onMob(shooter.getUniqueId(), each.getUniqueId(), world)) != Verdict.ALLOWED) {
            return false;
        }
        return shooter.hasLineOfSight(each);
    }

    private static String name(LivingEntity target) {
        return target instanceof Player player ? player.getName()
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(target.name());
    }
}
