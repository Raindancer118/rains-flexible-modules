package de.raindancer.modules.roles.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.roles.RolesServices;
import de.raindancer.modules.roles.model.Ability;
import de.raindancer.modules.roles.model.AbilityKind;
import de.raindancer.modules.roles.rules.AbilityRule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What a role does in the game: each event asks how much of which ability the player has now (it grows with
 * the role, like the perks) and changes what happens by that much. Speed and reach are attribute modifiers,
 * transient so they are never saved with the player and gone with the module.
 */
public final class AbilityListener implements IRolesListener {

    /** What a ripe crop gives one more of. */
    private static final Map<Material, Material> CROPS = Map.of(
            Material.WHEAT, Material.WHEAT,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT,
            Material.NETHER_WART, Material.NETHER_WART,
            Material.COCOA, Material.COCOA_BEANS,
            Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES);

    /** Vanilla's top speed for a minecart, blocks per tick. */
    private static final double CART_SPEED = 0.4;

    private final RolesServices services;
    private final AbilityRule rule = new AbilityRule();
    private final NamespacedKey speedKey;
    private final NamespacedKey reachKey;

    public AbilityListener(RolesServices services) {
        this.services = services;
        this.speedKey = new NamespacedKey(services.plugin(), "role-speed");
        this.reachKey = new NamespacedKey(services.plugin(), "role-reach");
    }

    private int size(Player player, AbilityKind kind) {
        return services.roles().abilityNow(player.getUniqueId(), kind);
    }

    private static double roll() {
        return ThreadLocalRandom.current().nextDouble();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player) {
            int percent = size(player, AbilityKind.HUNGER);
            if (percent > 0) {
                event.setExhaustion((float) rule.less(event.getExhaustion(), percent));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            int percent = size(player, AbilityKind.FALLS);
            if (percent > 0) {
                event.setDamage(rule.less(event.getDamage(), percent));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        // Monsters only: a role must never make one player hit another harder.
        if (!(event.getEntity() instanceof Enemy) || event.getEntity() instanceof Player) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (attacker != null) {
            int percent = size(attacker, AbilityKind.MONSTERS);
            if (percent > 0) {
                event.setDamage(rule.more(event.getDamage(), percent));
            }
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player ? player : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToolWear(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        int percent = services.roles().roleOf(player.getUniqueId()).stream()
                .flatMap(role -> role.abilities().stream())
                .filter(each -> each.kind() == AbilityKind.TOOLS && each.covers(item.getType().name()))
                .mapToInt(each -> services.roles().abilityNow(player.getUniqueId(), each)).max().orElse(0);
        if (percent > 0 && rule.happens(percent, roll())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExperience(PlayerExpChangeEvent event) {
        int percent = size(event.getPlayer(), AbilityKind.XP);
        if (percent > 0) {
            event.setAmount(rule.experience(event.getAmount(), percent, roll()));
        }
    }

    /** Ore mined by somebody lucky this time, until its drops appear — keyed by where it was. */
    private final java.util.Set<String> lucky = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String where(org.bukkit.block.Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    // Before MONITOR, where Core's PlacedBlocks takes the mark off: ore a player put there brings no luck.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOre(org.bukkit.event.block.BlockBreakEvent event) {
        org.bukkit.block.Block block = event.getBlock();
        if (!block.getType().name().endsWith("_ORE") || event.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE
                || !event.isDropItems() || de.raindancer.core.world.blocks.PlacedBlocks.isPlaced(block)) {
            return;
        }
        int percent = size(event.getPlayer(), AbilityKind.FORTUNE);
        if (percent > 0 && rule.happens(percent, roll())) {
            lucky.add(where(block));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOreDrops(BlockDropItemEvent event) {
        if (!lucky.remove(where(event.getBlock())) || event.getItems().isEmpty()) {
            return;
        }
        ItemStack first = event.getItems().getFirst().getItemStack();
        if (rule.lucky(event.getBlockState().getType().name(), first.getType().name())) {
            ItemStack one = first.clone();
            one.setAmount(1);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.25, 0.5), one);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(BlockDropItemEvent event) {
        Material extra = CROPS.get(event.getBlockState().getType());
        if (extra == null || event.getItems().isEmpty()
                || !(event.getBlockState().getBlockData() instanceof Ageable crop) || crop.getAge() < crop.getMaximumAge()) {
            return;
        }
        int percent = size(event.getPlayer(), AbilityKind.HARVEST);
        if (percent > 0 && rule.happens(percent, roll())) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.25, 0.5),
                    new ItemStack(extra));
        }
    }

    /** Animals bought as eggs, hatched by spawners or summoned are no butcher's work. */
    private static final java.util.Set<org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason> NOT_RAISED = java.util.Set.of(
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER,
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.COMMAND,
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);

    @EventHandler(priority = EventPriority.HIGH)
    public void onButcher(org.bukkit.event.entity.EntityDeathEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Animals animal) || animal.getKiller() == null
                || NOT_RAISED.contains(animal.getEntitySpawnReason())) {
            return;
        }
        int percent = size(animal.getKiller(), AbilityKind.BUTCHER);
        if (percent <= 0 || !rule.happens(percent, roll())) {
            return;
        }
        event.getDrops().stream().filter(drop -> drop != null && drop.getType().isEdible()).findFirst()
                .ifPresent(food -> {
                    ItemStack one = food.clone();
                    one.setAmount(1);
                    event.getDrops().add(one);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoard(VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Minecart cart && event.getEntered() instanceof Player player) {
            int percent = size(player, AbilityKind.CARTS);
            cart.setMaxSpeed(percent > 0 ? rule.more(CART_SPEED, percent) : CART_SPEED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeave(VehicleExitEvent event) {
        if (event.getVehicle() instanceof Minecart cart && event.getExited() instanceof Player) {
            cart.setMaxSpeed(CART_SPEED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    /** Puts speed and reach on at the size the player has now, or takes them off. On the player's own thread. */
    public void refresh(Player player) {
        Scheduling.entity(services.plugin(), player, () -> {
            if (player.isOnline()) {
                apply(player, Attribute.MOVEMENT_SPEED, speedKey, size(player, AbilityKind.SPEED));
                apply(player, Attribute.BLOCK_INTERACTION_RANGE, reachKey, size(player, AbilityKind.REACH));
            }
        });
    }

    public void refresh(UUID player) {
        Optional.ofNullable(services.server().getPlayer(player)).ifPresent(this::refresh);
    }

    /** Takes speed and reach off everybody — when the module stops. */
    public void strip() {
        for (Player player : services.server().getOnlinePlayers()) {
            Scheduling.entity(services.plugin(), player, () -> {
                apply(player, Attribute.MOVEMENT_SPEED, speedKey, 0);
                apply(player, Attribute.BLOCK_INTERACTION_RANGE, reachKey, 0);
            });
        }
    }

    private static void apply(Player player, Attribute attribute, NamespacedKey key, int percent) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        AttributeModifier current = instance.getModifier(key);
        double wanted = percent / 100.0;
        if (current != null && current.getAmount() == wanted) {
            return;
        }
        if (current != null) {
            instance.removeModifier(key);
        }
        if (percent > 0) {
            instance.addTransientModifier(new AttributeModifier(key, wanted, AttributeModifier.Operation.ADD_SCALAR,
                    EquipmentSlotGroup.ANY));
        }
    }

    @Override
    public void forget(UUID player) {
        // Transient modifiers leave with the player.
    }
}
