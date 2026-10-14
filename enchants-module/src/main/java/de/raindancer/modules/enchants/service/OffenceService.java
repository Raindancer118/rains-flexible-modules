package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Combat;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.rules.Offence;
import org.bukkit.GameMode;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fighting enchantments on a melee hit: what makes it harder (Overpower, Executioner, Berserk, Armor Pierce) before
 * armour has its say, and what follows it (Lifesteal, Bleed, Venom, Cleave) once it landed.
 */
public final class OffenceService {

    private final Plugin plugin;
    private final Combat combat;
    private final EnchantAdvancements advancements;
    /** Wounds bleeding now, by the creature that bleeds. */
    private final Map<UUID, Wound> wounds = new ConcurrentHashMap<>();
    /** Set while a Cleave swing deals its own hits, so those are not taken for swings of their own. */
    private final ThreadLocal<Boolean> cleaving = ThreadLocal.withInitial(() -> false);

    private static final class Wound {
        volatile int stacks;
        volatile int secondsLeft;
        volatile UUID attacker;
        volatile boolean againstPlayer;
    }

    public OffenceService(Plugin plugin, Combat combat, EnchantAdvancements advancements) {
        this.plugin = plugin;
        this.combat = combat;
        this.advancements = advancements;
    }

    public boolean cleaving() {
        return cleaving.get();
    }

    private static int level(ItemStack weapon, String key) {
        return weapon == null || weapon.isEmpty() ? 0 : CustomEnchantments.levelOn(weapon, key);
    }

    private static double value(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0 : instance.getValue();
    }

    /** @return the hit's damage with Rain's enchantments, before armour */
    public double strike(Player attacker, LivingEntity target, double damage) {
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        int overpower = level(weapon, RainsEnchants.OVERPOWER);
        int executioner = level(weapon, RainsEnchants.EXECUTIONER);
        int berserk = level(weapon, RainsEnchants.BERSERK);
        int pierce = level(weapon, RainsEnchants.ARMOR_PIERCE);
        if (overpower + executioner + berserk + pierce == 0) {
            return damage;
        }
        boolean againstPlayer = target instanceof Player;
        Offence.Hit hit = new Offence.Hit(damage, overpower, executioner, berserk, target.getHealth(),
                value(target, Attribute.MAX_HEALTH), attacker.getHealth(), value(attacker, Attribute.MAX_HEALTH),
                value(target, Attribute.ARMOR), value(target, Attribute.ARMOR_TOUGHNESS), againstPlayer);
        double harder = Offence.damage(hit);
        double extra = Offence.pierce(harder, hit.armour(), hit.toughness(), pierce, againstPlayer);
        UUID who = attacker.getUniqueId();
        if (overpower > 0) {
            advancements.used(who, RainsEnchants.OVERPOWER);
        }
        if (executioner > 0 && Offence.damage(new Offence.Hit(damage, 0, executioner, 0, hit.targetHealth(),
                hit.targetMost(), 0, 0, 0, 0, againstPlayer)) > damage) {
            advancements.used(who, RainsEnchants.EXECUTIONER);
        }
        if (berserk > 0 && attacker.getHealth() < hit.ownMost()) {
            advancements.used(who, RainsEnchants.BERSERK);
        }
        if (extra > 0) {
            advancements.used(who, RainsEnchants.ARMOR_PIERCE);
        }
        return harder + extra;
    }

    /** The hit landed and did {@code dealt}: what follows it. */
    public void landed(Player attacker, LivingEntity target, double dealt) {
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        boolean againstPlayer = target instanceof Player;
        UUID who = attacker.getUniqueId();

        double healed = Offence.lifesteal(level(weapon, RainsEnchants.LIFESTEAL), dealt, againstPlayer);
        if (healed > 0 && !attacker.isDead()) {
            attacker.heal(healed);
            advancements.used(who, RainsEnchants.LIFESTEAL);
        }
        int venom = level(weapon, RainsEnchants.VENOM);
        if (venom > 0 && !target.isDead()) {
            for (Offence.Dose dose : Offence.venom(venom, againstPlayer)) {
                PotionEffectType type = Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(dose.effect()));
                if (type != null && dose.ticks() > 0) {
                    target.addPotionEffect(new PotionEffect(type, dose.ticks(), dose.amplifier()));
                }
            }
            advancements.used(who, RainsEnchants.VENOM);
        }
        int bleed = level(weapon, RainsEnchants.BLEED);
        if (bleed > 0 && !target.isDead()) {
            wound(attacker, target, bleed, againstPlayer);
            advancements.used(who, RainsEnchants.BLEED);
        }
        int cleave = level(weapon, RainsEnchants.CLEAVE);
        if (cleave > 0 && dealt > 0) {
            cleave(attacker, target, cleave, dealt);
        }
    }

    private void wound(Player attacker, LivingEntity target, int level, boolean againstPlayer) {
        Wound wound = wounds.computeIfAbsent(target.getUniqueId(), id -> new Wound());
        boolean fresh;
        synchronized (wound) {
            fresh = wound.stacks == 0;
            wound.stacks = Offence.bleedStacks(level, wound.stacks);
            wound.secondsLeft = Offence.BLEED_TICKS / 20;
            wound.attacker = attacker.getUniqueId();
            wound.againstPlayer = againstPlayer;
        }
        if (!fresh) {
            return;
        }
        Scheduling.entityTimer(plugin, target, 20L, 20L, task -> {
            if (!target.isValid() || target.isDead() || wound.secondsLeft-- <= 0) {
                wounds.remove(target.getUniqueId());
                task.cancel();
                return;
            }
            Player by = plugin.getServer().getPlayer(wound.attacker);
            DamageSource.Builder source = DamageSource.builder(DamageType.MAGIC);
            if (by != null) {
                source.withCausingEntity(by).withDirectEntity(by);
            }
            target.damage(Offence.bleedPerSecond(wound.stacks, wound.againstPlayer), source.build());
        }, () -> wounds.remove(target.getUniqueId()));
    }

    private void cleave(Player attacker, LivingEntity target, int level, double dealt) {
        List<LivingEntity> others = target.getLocation().getNearbyLivingEntities(Offence.CLEAVE_RADIUS).stream()
                .filter(each -> !each.equals(target) && !each.equals(attacker) && strikable(attacker, each))
                .sorted(Comparator.comparingDouble(each -> each.getLocation().distanceSquared(target.getLocation())))
                .limit(Offence.cleaveTargets(level))
                .toList();
        if (others.isEmpty()) {
            return;
        }
        cleaving.set(true);
        try {
            for (LivingEntity each : others) {
                each.damage(dealt * Offence.cleaveShare(level, each instanceof Player), attacker);
            }
        } finally {
            cleaving.set(false);
        }
        advancements.cleaved(attacker.getUniqueId(), others.size());
    }

    /** Monsters, and players the server lets this one hurt — never pets, villagers or the harmless. */
    private boolean strikable(Player attacker, LivingEntity each) {
        if (each.isDead() || each instanceof ArmorStand
                || each instanceof Tameable pet && attacker.getUniqueId().equals(pet.getOwnerUniqueId())) {
            return false;
        }
        String world = attacker.getWorld().getName();
        if (each instanceof Player player) {
            return player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                    && combat.judge(Attack.between(attacker.getUniqueId(), player.getUniqueId(), world)) == Verdict.ALLOWED;
        }
        return each instanceof Enemy
                && combat.judge(Attack.onMob(attacker.getUniqueId(), each.getUniqueId(), world)) == Verdict.ALLOWED;
    }
}
