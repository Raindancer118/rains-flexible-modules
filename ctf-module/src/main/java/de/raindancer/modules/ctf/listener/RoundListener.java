package de.raindancer.modules.ctf.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Fighter;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.service.ArenaWorldService;
import de.raindancer.modules.ctf.service.RoleService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.UUID;

/**
 * What a round needs from the game: a blow that would kill is a death of the round's kind instead (no drops, no death
 * screen), the flag cannot be put down or moved about but can be thrown, the classes' abilities, the twists that are
 * rules, and blocks placed and broken only as the round allows.
 */
public final class RoundListener implements ICtfListener {

    private final CtfServices services;

    public RoundListener(CtfServices services) {
        this.services = services;
    }

    private boolean inRound(Player player) {
        return services.rounds().isIn(player.getUniqueId());
    }

    // ------------------------------------------------------------------ hurting and falling

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity && services.announcer().isShowRocket(byEntity.getDamager())) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        UUID id = victim.getUniqueId();
        if (!inRound(victim)) {
            if (services.lobby().isInside(id) && !services.arena().isArenaWorld(victim.getWorld().getName())) {
                // Nobody gets hurt waiting in the lobby.
                event.setCancelled(true);
            }
            return;
        }
        Optional<Fighter> fighter = services.rounds().fighter(id);
        if (fighter.isEmpty() || !fighter.get().isAlive() || services.rounds().isFrozen()) {
            event.setCancelled(true);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            services.rounds().fellOff(victim);
            return;
        }
        Optional<UUID> attacker = attacker(event);
        attacker.ifPresent(by -> services.rounds().hurt(id, by));
        if (attacker.isPresent() && services.rounds().isOneHit()
                && services.rounds().sideOf(attacker.get()).isPresent()) {
            event.setDamage(1000);
        }
        if (attacker.isPresent() && services.rounds().isKnockback()
                && event instanceof EntityDamageByEntityEvent byEntity) {
            Vector push = victim.getLocation().toVector().subtract(byEntity.getDamager().getLocation().toVector())
                    .setY(0).normalize().multiply(1.6).setY(0.55);
            if (Double.isFinite(push.getX())) {
                Scheduling.entityLater(services.plugin(), victim, 1L, () -> victim.setVelocity(push));
            }
        }
        boolean totem = victim.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || victim.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        if (event.getFinalDamage() >= victim.getHealth() && !totem) {
            event.setCancelled(true);
            services.rounds().killed(victim, attacker.orElse(null));
        }
    }

    /** Hitting somebody ends a ninja's vanishing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player hitter && services.roles().isVanished(hitter.getUniqueId())) {
            services.rounds().fighter(hitter.getUniqueId()).ifPresent(fighter -> services.rounds().round()
                    .ifPresent(round -> services.roles().reveal(hitter, round.side(fighter.side()).colour())));
        }
    }

    private static Optional<UUID> attacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return Optional.empty();
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty();
        }
        return damager instanceof Player player ? Optional.of(player.getUniqueId()) : Optional.empty();
    }

    /** An archer's arrow makes a flag carrier glow for everybody. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArrow(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof Player hit) || !(event.getEntity().getShooter() instanceof Player shooter)) {
            return;
        }
        boolean archer = services.rounds().fighter(shooter.getUniqueId()).map(Fighter::role)
                .filter(role -> role == Role.ARCHER).isPresent();
        if (archer && services.rounds().isCarrying(hit.getUniqueId())) {
            services.rounds().mark(hit.getUniqueId(), 5);
        }
    }

    /** A death the round did not catch (a plugin's /kill): nothing dropped, the round counts it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!inRound(player)) {
            return;
        }
        event.getDrops().clear();
        event.setKeepInventory(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        services.rounds().killed(player, null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (inRound(event.getPlayer())) {
            services.rounds().round().ifPresent(round -> event.setRespawnLocation(
                    event.getPlayer().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && services.lobby().isInside(player.getUniqueId())
                && (!inRound(player) || !services.config().get().hunger()) && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ standing still at the start

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition() || !services.rounds().isFrozen() || !inRound(event.getPlayer())
                || event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    // ------------------------------------------------------------------ the flag in hand

    /**
     * Not ignoring cancelled drops: a locked world cancels every drop before this is asked, and dropping the flag is
     * how a carrier throws it.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (services.flags().isFlagItem(dropped)) {
            event.setCancelled(true);
            if (services.config().get().throwing()) {
                Scheduling.entity(services.plugin(), event.getPlayer(), () ->
                        services.rounds().throwFlag(event.getPlayer()));
            }
            return;
        }
        if (services.lobby().purposeOf(dropped).isPresent() || (inRound(event.getPlayer())
                && event.getPlayer().getGameMode() != GameMode.CREATIVE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (services.flags().isFlagItem(event.getCurrentItem()) || services.flags().isFlagItem(event.getCursor())
                || (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                && services.flags().isFlagItem(player.getInventory().getItem(event.getHotbarButton())))) {
            event.setCancelled(true);
            return;
        }
        if (services.lobby().isInside(player.getUniqueId()) && !inRound(player)
                && services.lobby().purposeOf(event.getCurrentItem()).isPresent()) {
            event.setCancelled(true);
        }
        if (inRound(player) && event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
        }
    }

    /**
     * Nobody in a round opens anything but their own inventory and the plugin's menus: with building on, an ender
     * chest or a placed chest would carry kit items out of the game.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !inRound(player)) {
            return;
        }
        org.bukkit.event.inventory.InventoryType type = event.getInventory().getType();
        boolean own = type == org.bukkit.event.inventory.InventoryType.PLAYER
                || type == org.bukkit.event.inventory.InventoryType.CRAFTING
                || event.getInventory().getHolder() instanceof de.raindancer.core.ui.menu.Menu;
        if (!own) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (services.flags().isFlagItem(event.getMainHandItem()) || services.flags().isFlagItem(event.getOffHandItem())
                || services.lobby().purposeOf(event.getOffHandItem()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (services.flags().isFlagItem(held)) {
            event.setCancelled(true);
            if (event.getAction().isRightClick()) {
                services.rounds().throwFlag(player);
            }
            return;
        }
        if (!inRound(player) || !event.getAction().isRightClick()) {
            return;
        }
        if (held != null && held.getType() == Material.FIREWORK_ROCKET && player.isGliding()
                && services.rounds().isCarrying(player.getUniqueId())) {
            event.setCancelled(true);
            services.messages().send(player, "ctf.flag.no-rockets");
            return;
        }
        Optional<String> ability = services.roles().abilityOf(held);
        if (ability.isEmpty()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        switch (ability.get()) {
            case RoleService.VANISH -> {
                event.setCancelled(true);
                vanish(player);
            }
            case RoleService.WEB, RoleService.ALARM -> {
                event.setCancelled(true);
                if (clicked == null || event.getBlockFace() == null) {
                    return;
                }
                Block at = clicked.getRelative(event.getBlockFace());
                if (!at.getType().isAir() || !services.rounds().mayTrap(player, at)) {
                    services.messages().send(player, "ctf.trap.not-here");
                    return;
                }
                if (RoleService.WEB.equals(ability.get())) {
                    services.rounds().rewind().remember(at);
                    at.setType(Material.COBWEB, false);
                    services.rounds().device(RoleService.WEB, player, at, 20);
                } else {
                    services.rounds().device(RoleService.ALARM, player, at, 120);
                    services.messages().send(player, "ctf.trap.alarm-set");
                }
                held.setAmount(held.getAmount() - 1);
                services.core().effects().play(player.getUniqueId(), Cues.CLICK);
            }
            default -> {
            }
        }
    }

    private void vanish(Player player) {
        if (services.rounds().isCarrying(player.getUniqueId())) {
            services.messages().send(player, "ctf.class.no-vanish-carrying");
            return;
        }
        long wait = services.roles().vanish(player);
        if (wait > 0) {
            services.messages().send(player, "ctf.class.vanish-wait", "seconds", wait);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking() && inRound(player) && services.rounds().fighter(player.getUniqueId())
                .filter(fighter -> fighter.role() == Role.NINJA && fighter.isAlive()).isPresent()
                && !services.roles().isVanished(player.getUniqueId())) {
            vanish(player);
        }
    }

    /** A medic mends a teammate. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMend(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player medic = event.getPlayer();
        ItemStack held = medic.getInventory().getItemInMainHand();
        if (!RoleService.MEND.equals(services.roles().abilityOf(held).orElse(null))) {
            return;
        }
        event.setCancelled(true);
        if (services.rounds().mend(medic, target)) {
            services.messages().send(medic, "ctf.class.mended", "player", target.getName());
            services.messages().send(target, "ctf.class.mended-by", "player", medic.getName());
        } else {
            services.messages().send(medic, "ctf.class.cannot-mend");
        }
    }

    // ------------------------------------------------------------------ moving about

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDoubleJump(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!inRound(player) || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        event.setCancelled(true);
        if (!event.isFlying() || !services.rounds().doubleJump(player.getUniqueId())) {
            player.setAllowFlight(false);
            return;
        }
        player.setFlying(false);
        player.setVelocity(player.getLocation().getDirection().multiply(0.6).setY(0.9));
        services.core().effects().play(player.getUniqueId(), Cues.ABILITY);
        if (services.rounds().doubleJumpsLeft(player.getUniqueId()) <= 0) {
            player.setAllowFlight(false);
        }
    }

    /** The grappling hook pulls its holder to where the hook caught. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrapple(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (!inRound(player) || !RoleService.GRAPPLE.equals(services.roles().abilityOf(
                player.getInventory().getItemInMainHand()).orElse(null))) {
            return;
        }
        if (event.getState() != PlayerFishEvent.State.IN_GROUND && event.getState() != PlayerFishEvent.State.REEL_IN
                && event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) {
            return;
        }
        FishHook hook = event.getHook();
        Vector pull = hook.getLocation().toVector().subtract(player.getLocation().toVector());
        double distance = pull.length();
        if (distance < 2 || distance > 40) {
            return;
        }
        Vector speed = pull.normalize().multiply(Math.min(2.6, 0.6 + distance * 0.08)).setY(Math.min(1.4,
                0.5 + pull.getY() * 0.06));
        Scheduling.entity(services.plugin(), player, () -> player.setVelocity(speed));
        services.core().effects().play(player.getUniqueId(), Cues.ABILITY);
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!services.arena().isArenaWorld(player.getWorld().getName()) || ArenaWorldService.isStaffBuilding(player)) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (services.flags().isFlagItem(event.getItemInHand()) || !services.rounds().mayPlace(player, block)) {
            event.setCancelled(true);
            return;
        }
        services.rounds().placedBlock(block);
        String ability = services.roles().abilityOf(event.getItemInHand()).orElse("");
        if (RoleService.TURRET.equals(ability)) {
            services.rounds().device(RoleService.TURRET, player, block, 40);
            services.messages().send(player, "ctf.class.turret-up");
        } else if (RoleService.PAD.equals(ability)) {
            Block above = block.getRelative(BlockFace.UP);
            if (above.getType().isAir()) {
                services.rounds().rewind().remember(above);
                above.setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, false);
                services.rounds().placedBlock(above);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!services.arena().isArenaWorld(player.getWorld().getName()) || ArenaWorldService.isStaffBuilding(player)) {
            return;
        }
        if (!services.rounds().mayBreak(player, event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    /** Spleef: a shovel breaks a block at a touch. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDig(BlockDamageEvent event) {
        if (services.rounds().isSpleef() && inRound(event.getPlayer())
                && event.getItemInHand().getType().name().endsWith("_SHOVEL")
                && services.rounds().mayBreak(event.getPlayer(), event.getBlock())) {
            event.setInstaBreak(true);
        }
    }
}
