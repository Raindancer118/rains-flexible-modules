package de.raindancer.modules.manhunt.stats;

import de.raindancer.modules.manhunt.model.Hunt;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The moments of a hunt worth a title, read off the game as they happen — registered per run.
 *
 * <p>The Nether and the End are read off the world a Runner arrives in, a blaze rod off the pickup,
 * so neither depends on advancements being cleared at the start; the fortress and the stronghold have
 * no other honest signal than their advancement. The dragon is anybody's, in this run's worlds only.
 */
public final class HuntRecorder implements Listener {

    private static final Map<String, Milestone> ADVANCEMENTS = Map.of(
            "minecraft:nether/find_fortress", Milestone.FORTRESS,
            "minecraft:story/follow_ender_eye", Milestone.STRONGHOLD,
            "minecraft:story/enter_the_nether", Milestone.NETHER,
            "minecraft:story/enter_the_end", Milestone.END,
            "minecraft:nether/obtain_blaze_rod", Milestone.BLAZE_ROD);

    private final Hunt hunt;
    private final Predicate<String> runWorld;
    private final BiConsumer<Milestone, Player> reached;
    private final Consumer<UUID> portal;
    private final ToDoubleFunction<LivingEntity> maxHealth;

    /**
     * @param reached   a milestone and who reached it — null for nobody in particular
     * @param maxHealth an entity's maximum health; a seam because the attribute registry needs a server
     */
    public HuntRecorder(Hunt hunt, Predicate<String> runWorld, BiConsumer<Milestone, Player> reached,
                        Consumer<UUID> portal, ToDoubleFunction<LivingEntity> maxHealth) {
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.runWorld = Objects.requireNonNull(runWorld, "runWorld");
        this.reached = Objects.requireNonNull(reached, "reached");
        this.portal = Objects.requireNonNull(portal, "portal");
        this.maxHealth = Objects.requireNonNull(maxHealth, "maxHealth");
    }

    private boolean runnerInIt(Player player) {
        return hunt.isRunner(player.getUniqueId()) && !hunt.isEliminated(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World now = player.getWorld();
        if (!runnerInIt(player) || now == null) {
            return;
        }
        switch (now.getEnvironment()) {
            case NETHER -> reached.accept(Milestone.NETHER, player);
            case THE_END -> reached.accept(Milestone.END, player);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && runnerInIt(player)
                && event.getItem().getItemStack().getType() == Material.BLAZE_ROD) {
            reached.accept(Milestone.BLAZE_ROD, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        NamespacedKey key = event.getAdvancement().getKey();
        Milestone milestone = key == null ? null : ADVANCEMENTS.get(key.toString());
        if (milestone != null && runnerInIt(event.getPlayer())) {
            reached.accept(milestone, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !runWorld.test(dragon.getWorld().getName())) {
            return;
        }
        double half = maxHealth.applyAsDouble(dragon) / 2;
        double before = dragon.getHealth();
        if (before > half && before - event.getFinalDamage() <= half) {
            reached.accept(Milestone.DRAGON_HALF, damager(event));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon && runWorld.test(dragon.getWorld().getName())) {
            reached.accept(Milestone.DRAGON_KILLED, dragon.getKiller());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (hunt.everybody().contains(event.getPlayer().getUniqueId())) {
            portal.accept(event.getPlayer().getUniqueId());
        }
    }

    private static Player damager(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Player player) {
                return player;
            }
            if (byEntity.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
                return shooter;
            }
        }
        return null;
    }

    public String describe() {
        return "the moments of a hunt: the Nether, a fortress, a blaze rod, the stronghold, the End, the dragon";
    }
}
