package de.raindancer.modules.enchants.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.enchants.rules.ShardSpawnRule;
import de.raindancer.modules.enchants.rules.ShardTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Bee;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The creatures of the shard world: only hostile ones, the neutral kinds always angry ({@link ShardSpawnRule}); each
 * that spawns by itself becomes one of the {@link ShardTier} kinds —
 * named, and tougher the rarer it is. The kind is kept on the monster, so its drop knows what it was.
 */
public final class ShardMobService {

    public static final NamespacedKey TIER = new NamespacedKey("rainsenchants", "shard_tier");
    private static final NamespacedKey BOOST = new NamespacedKey("rainsenchants", "shard_tier_boost");

    /** How far an always-angry creature looks for somebody to go for. */
    private static final double HUNT_BLOCKS = 24;
    /** As long as a built wither charges before it fights: the game's own. */
    private static final int WITHER_CHARGE_TICKS = 220;
    private static final int ANGER_TICKS = 400;

    private final Plugin plugin;
    private final ShardWorldService worlds;
    /** Creatures already hunting: a chunk made with its creatures announces them twice. */
    private final java.util.Set<java.util.UUID> hunting = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ShardMobService(Plugin plugin, ShardWorldService worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
    }

    /**
     * Whether a creature may come into the shard world by itself at all: only monsters and the neutral kinds made
     * to hunt. Eggs, spawners and commands are staff's and pass.
     */
    public boolean refused(LivingEntity entity, CreatureSpawnEvent.SpawnReason reason) {
        return bySelf(reason) && worlds.isShardWorld(entity.getWorld())
                && ShardSpawnRule.of(entity.getType()) == ShardSpawnRule.REFUSED;
    }

    /** A creature of the shard world came into memory (spawned, or its chunk loaded): the neutral ones start hunting. */
    public void loaded(LivingEntity entity) {
        if (entity instanceof org.bukkit.entity.AbstractSkeleton skeleton && de.raindancer.modules.enchants.rules.Tyrant.powers(tierOf(skeleton).orElse(null))
                && worlds.isShardWorld(skeleton.getWorld()) && gunners.add(skeleton.getUniqueId())) {
            int[] fight = {0};
            Scheduling.entityTimer(plugin, skeleton, 1, 1, task -> minigun(skeleton, fight),
                    () -> gunners.remove(skeleton.getUniqueId()));
        }
        if (entity instanceof Mob mob && tierOf(mob).isPresent() && worlds.isShardWorld(mob.getWorld())
                && ShardSpawnRule.of(mob.getType()) == ShardSpawnRule.ENRAGED && hunting.add(mob.getUniqueId())) {
            Scheduling.entityTimer(plugin, mob, 20, 20, task -> hunt(mob), () -> hunting.remove(mob.getUniqueId()));
        }
    }

    /**
     * A creature next to {@code near} as if it had come by itself — named, tiered and angry like one; refused like
     * one too. For staff and events.
     *
     * @return the creature, or empty when the shard world does not take that kind
     */
    public java.util.Optional<LivingEntity> summon(Player near, org.bukkit.entity.EntityType type) {
        // Not checked against the rule here: the spawn goes through the same refusal as one in the dark.
        Class<?> kind = type.getEntityClass();
        // Bosses never come by themselves, so they are not anybody's to call either.
        if (!worlds.isShardWorld(near.getWorld()) || !type.isAlive() || !type.isSpawnable()
                || kind == null || org.bukkit.entity.Boss.class.isAssignableFrom(kind)) {
            return java.util.Optional.empty();
        }
        // At the player's feet: a spot ahead may be rock on amplified ground, and a creature in rock suffocates.
        org.bukkit.Location at = near.getLocation();
        org.bukkit.entity.Entity made = near.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.NATURAL);
        return made instanceof LivingEntity living && made.isValid() ? java.util.Optional.of(living) : java.util.Optional.empty();
    }

    /** Whether a target is still worth chasing: in the same world (gone through a portal, it is not) and near enough. */
    static boolean stillChasing(org.bukkit.Location target, org.bukkit.Location mob, double within) {
        return target.getWorld() != null && target.getWorld().equals(mob.getWorld())
                && target.distanceSquared(mob) <= within * within;
    }

    /**
     * Raises a Crystal Wither where its build stood: the game's wither, of the Crystal kind, charging up as a built
     * wither does. Only in the shard world.
     */
    public org.bukkit.entity.Wither crystalWither(org.bukkit.Location base) {
        org.bukkit.entity.Wither wither = base.getWorld().spawn(base.clone().add(0.5, 0, 0.5), org.bukkit.entity.Wither.class,
                made -> made.setInvulnerableTicks(WITHER_CHARGE_TICKS));
        giveKind(wither, ShardTier.CRYSTAL);
        wither.getPersistentDataContainer().set(CRYSTAL_WITHER, PersistentDataType.BYTE, (byte) 1);
        return wither;
    }

    private static final org.bukkit.NamespacedKey CRYSTAL_WITHER = new org.bukkit.NamespacedKey("rainsenchants", "crystal_wither");

    /** Whether this is a Crystal Wither risen from its build — not a wither from a command or an egg. */
    public static boolean isCrystalWither(org.bukkit.entity.Entity entity) {
        return entity instanceof org.bukkit.entity.Wither && entity.getPersistentDataContainer().has(CRYSTAL_WITHER);
    }

    private final java.util.Set<java.util.UUID> gunners = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final org.bukkit.NamespacedKey CALLED = new org.bukkit.NamespacedKey("rainsenchants", "tyrant_called");
    /** How far a skeleton tyrant fires, and how hard one arrow hits before its kind's multiplier. */
    private static final double MINIGUN_RANGE = 32;
    private static final double MINIGUN_ARROW_DAMAGE = 0.4;
    private static final double MINIGUN_SPEED = 2.6;
    private static final double MINIGUN_SPREAD = 0.06;

    /** One tick of a skeleton tyrant's fight: bursts of arrows at the player it is after, while it can see them. */
    private void minigun(org.bukkit.entity.AbstractSkeleton skeleton, int[] fight) {
        if (!(skeleton.getTarget() instanceof Player target) || !huntable(target)
                || !stillChasing(target.getLocation(), skeleton.getLocation(), MINIGUN_RANGE)
                || !skeleton.hasLineOfSight(target)) {
            fight[0] = 0;
            return;
        }
        if (de.raindancer.modules.enchants.rules.Tyrant.fires(fight[0]++)) {
            org.bukkit.util.Vector aim = target.getEyeLocation().subtract(0, 0.3, 0).toVector()
                    .subtract(skeleton.getEyeLocation().toVector()).normalize();
            java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            aim.add(new org.bukkit.util.Vector(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
                    .multiply(MINIGUN_SPREAD));
            org.bukkit.entity.Arrow arrow = skeleton.launchProjectile(org.bukkit.entity.Arrow.class,
                    aim.normalize().multiply(MINIGUN_SPEED));
            arrow.setDamage(MINIGUN_ARROW_DAMAGE);
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            skeleton.getWorld().playSound(skeleton.getLocation(), org.bukkit.Sound.ENTITY_ARROW_SHOOT, 0.6f, 1.8f);
        }
    }

    /**
     * Something hit a creature: a zombie tyrant taken below half its health calls for help, once — zombies of its own
     * kind beside it, Shardborn, after whoever hit it. Also: a skeleton tyrant's arrow leaves no moment of invulnerability,
     * or the minigun's every other arrow would bounce off.
     */
    public void hurt(LivingEntity victim, double damage, org.bukkit.entity.Entity source) {
        if (source instanceof org.bukkit.entity.AbstractArrow arrow
                && arrow.getShooter() instanceof org.bukkit.entity.AbstractSkeleton gunner
                && de.raindancer.modules.enchants.rules.Tyrant.powers(tierOf(gunner).orElse(null))) {
            Scheduling.entityLater(plugin, victim, 1L, () -> victim.setNoDamageTicks(0));
        }
        if (!(victim instanceof org.bukkit.entity.Zombie tyrant)
                || !de.raindancer.modules.enchants.rules.Tyrant.powers(tierOf(tyrant).orElse(null))) {
            return;
        }
        org.bukkit.attribute.AttributeInstance most = tyrant.getAttribute(Attribute.MAX_HEALTH);
        double max = most == null ? tyrant.getHealth() : most.getValue();
        boolean called = tyrant.getPersistentDataContainer().has(CALLED);
        if (!de.raindancer.modules.enchants.rules.Tyrant.callsForHelp(tyrant.getHealth(), tyrant.getHealth() - damage,
                max, called)) {
            return;
        }
        tyrant.getPersistentDataContainer().set(CALLED, PersistentDataType.BYTE, (byte) 1);
        LivingEntity attacker = source instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter ? shooter
                : source instanceof LivingEntity living ? living : null;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        ShardTier kind = tierOf(tyrant).orElseThrow();
        for (int i = 0; i < de.raindancer.modules.enchants.rules.Tyrant.helpers(kind); i++) {
            org.bukkit.Location at = tyrant.getLocation().add(random.nextDouble(-2, 2), 0, random.nextDouble(-2, 2));
            org.bukkit.entity.Entity made = tyrant.getWorld().spawn(at, tyrant.getType().getEntityClass());
            if (made instanceof org.bukkit.entity.Zombie helper) {
                giveKind(helper, de.raindancer.modules.enchants.rules.Tyrant.helperKind(kind));
                if (attacker instanceof Player player && huntable(player)) {
                    helper.setTarget(player);
                }
            }
        }
        tyrant.getWorld().playSound(tyrant.getLocation(), org.bukkit.Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1f, 0.5f);
    }

    /** Keeps an always-angry creature on the nearest player who can be hurt, angry the way its kind gets angry. */
    private static void hunt(Mob mob) {
        if (mob.getTarget() instanceof Player held && huntable(held)
                && stillChasing(held.getLocation(), mob.getLocation(), HUNT_BLOCKS * 2)) {
            return;
        }
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : mob.getLocation().getNearbyPlayers(HUNT_BLOCKS)) {
            double distance = player.getLocation().distanceSquared(mob.getLocation());
            if (huntable(player) && distance < best) {
                best = distance;
                nearest = player;
            }
        }
        if (nearest == null) {
            return;
        }
        switch (mob) {
            case Wolf wolf -> wolf.setAngry(true);
            case Bee bee -> bee.setAnger(ANGER_TICKS);
            case PigZombie piglin -> {
                piglin.setAngry(true);
                piglin.setAnger(ANGER_TICKS);
            }
            case IronGolem golem -> golem.setPlayerCreated(false);
            default -> {
            }
        }
        mob.setTarget(nearest);
    }

    private static boolean huntable(Player player) {
        return player.isValid() && !player.isDead()
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
    }

    public void spawned(LivingEntity entity, CreatureSpawnEvent.SpawnReason reason) {
        if (!(entity instanceof Mob mob) || !bySelf(reason) || !worlds.isShardWorld(entity.getWorld())) {
            return;
        }
        giveKind(mob, ShardTier.roll(ThreadLocalRandom.current()));
    }

    /** Makes a creature one of the shard world's: its kind's name, strength and shards. */
    public void giveKind(Mob mob, ShardTier tier) {
        mob.getPersistentDataContainer().set(TIER, PersistentDataType.STRING, tier.key());
        mob.customName(MiniMessage.miniMessage().deserialize(tier.title(),
                Placeholder.component("name", Component.translatable(mob.getType().translationKey()))));
        mob.setCustomNameVisible(tier.nameShown());
        // A name alone does not keep a monster for ever (a name tag does that by other means); said anyway, so the
        // night never fills up with monsters that cannot leave.
        mob.setRemoveWhenFarAway(true);
        multiply(mob, Attribute.MAX_HEALTH, tier.healthFactor() - 1);
        add(mob, Attribute.ATTACK_DAMAGE, tier.extraDamage());
        boolean babyZombie = mob instanceof org.bukkit.entity.Zombie zombie && !zombie.isAdult();
        multiply(mob, Attribute.MOVEMENT_SPEED, tier.speedFactor(babyZombie) - 1);
        add(mob, Attribute.ARMOR, tier.armour());
        AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            mob.setHealth(health.getValue());
        }
        if (de.raindancer.modules.enchants.rules.Tyrant.powers(tier)) {
            mob.setGlowing(true);
        }
        loaded(mob);
    }

    /**
     * Came by itself: spawned in the dark or made with the land. Bred, hatched, spawner, egg, command and every
     * other made-to-order spawn never gets a kind, so no farm of any sort yields shards.
     */
    public static boolean bySelf(CreatureSpawnEvent.SpawnReason reason) {
        return reason == CreatureSpawnEvent.SpawnReason.NATURAL || reason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN;
    }

    /** The kind it was given, empty for one that never got one. */
    public static java.util.Optional<ShardTier> tierOf(LivingEntity entity) {
        String key = entity.getPersistentDataContainer().get(TIER, PersistentDataType.STRING);
        return key == null ? java.util.Optional.empty() : java.util.Optional.of(ShardTier.of(key));
    }

    private static void multiply(Mob mob, Attribute attribute, double by) {
        modify(mob, attribute, by, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }

    private static void add(Mob mob, Attribute attribute, double amount) {
        modify(mob, attribute, amount, AttributeModifier.Operation.ADD_NUMBER);
    }

    private static void modify(Mob mob, Attribute attribute, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null || amount <= 0) {
            return;
        }
        instance.removeModifier(BOOST);
        instance.addModifier(new AttributeModifier(BOOST, amount, operation, EquipmentSlotGroup.ANY));
    }
}
