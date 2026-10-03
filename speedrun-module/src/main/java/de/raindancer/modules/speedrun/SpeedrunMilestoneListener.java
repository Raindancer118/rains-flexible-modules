package de.raindancer.modules.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Notices a run passing its milestones, for one run: registered with it, unregistered with it.
 *
 * <h2>Two ways in for most of them</h2>
 * The advancement is the cheap, exact signal — vanilla already decides when somebody "found a
 * fortress". But an advancement is granted once per player, ever, and a server can switch off
 * clearing them at the start ({@code clear-advancements-on-start}). So whatever can also be seen
 * directly is: a world change for the nether and the End, an item picked up for a blaze rod, the
 * dragon's own damage and death. The split itself only happens once, whichever arrives first.
 *
 * <h2>Only the run's own worlds, only racers</h2>
 * A dragon killed in the server's own End, or a spectator's blaze rod, splits nothing.
 */
public final class SpeedrunMilestoneListener implements Listener {

    private static final Map<String, SpeedrunMilestone> BY_ADVANCEMENT = Map.of(
            "minecraft:story/enter_the_nether", SpeedrunMilestones.ENTER_NETHER,
            "minecraft:nether/find_bastion", SpeedrunMilestones.BASTION,
            "minecraft:nether/find_fortress", SpeedrunMilestones.FORTRESS,
            "minecraft:nether/obtain_blaze_rod", SpeedrunMilestones.BLAZE_ROD,
            "minecraft:story/follow_ender_eye", SpeedrunMilestones.STRONGHOLD,
            "minecraft:story/enter_the_end", SpeedrunMilestones.ENTER_END,
            "minecraft:end/kill_dragon", SpeedrunMilestones.DRAGON_KILL);

    private final SpeedrunSession session;
    private final SpeedrunSplitTracker tracker;
    private final SpeedrunWorlds worlds;
    private final IntSupplier pearlTarget;
    private final ToDoubleFunction<EnderDragon> maxHealth;
    private final Predicate<UUID> counts;

    public SpeedrunMilestoneListener(SpeedrunSession session, SpeedrunSplitTracker tracker,
                                     SpeedrunWorlds worlds, IntSupplier pearlTarget) {
        this(session, tracker, worlds, pearlTarget, SpeedrunMilestoneListener::maxHealthOf, racer -> true);
    }

    /**
     * @param counts whose progress splits the run — every racer in a race; in Manhunt only a Runner
     *               still running ({@code SpeedrunMode.countsForGoal}), since a Hunter in the Nether is
     *               no news about the Runners' run
     */
    public SpeedrunMilestoneListener(SpeedrunSession session, SpeedrunSplitTracker tracker,
                                     SpeedrunWorlds worlds, IntSupplier pearlTarget, Predicate<UUID> counts) {
        this(session, tracker, worlds, pearlTarget, SpeedrunMilestoneListener::maxHealthOf, counts);
    }

    /** For tests: the attribute registry needs a running server. */
    SpeedrunMilestoneListener(SpeedrunSession session, SpeedrunSplitTracker tracker, SpeedrunWorlds worlds,
                              IntSupplier pearlTarget, ToDoubleFunction<EnderDragon> maxHealth) {
        this(session, tracker, worlds, pearlTarget, maxHealth, racer -> true);
    }

    SpeedrunMilestoneListener(SpeedrunSession session, SpeedrunSplitTracker tracker, SpeedrunWorlds worlds,
                              IntSupplier pearlTarget, ToDoubleFunction<EnderDragon> maxHealth,
                              Predicate<UUID> counts) {
        this.session = session;
        this.tracker = tracker;
        this.worlds = worlds;
        this.pearlTarget = pearlTarget;
        this.maxHealth = maxHealth;
        this.counts = counts == null ? racer -> true : counts;
    }

    private static double maxHealthOf(EnderDragon dragon) {
        AttributeInstance max = dragon.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 200.0 : max.getValue();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!racing(player)) {
            return;
        }
        String from = nameOf(event.getFrom());
        String to = nameOf(player.getWorld());
        if (worlds.nether().equalsIgnoreCase(to)) {
            tracker.reach(SpeedrunMilestones.ENTER_NETHER.id(), player.getUniqueId());
        } else if (worlds.theEnd().equalsIgnoreCase(to)) {
            tracker.reach(SpeedrunMilestones.ENTER_END.id(), player.getUniqueId());
        } else if (worlds.overworld().equalsIgnoreCase(to) && worlds.nether().equalsIgnoreCase(from)) {
            tracker.reach(SpeedrunMilestones.LEAVE_NETHER.id(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        SpeedrunMilestone milestone = BY_ADVANCEMENT.get(event.getAdvancement().getKey().asString());
        if (milestone != null && racing(player) && worlds.contains(nameOf(player.getWorld()))) {
            tracker.reach(milestone.id(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !racing(player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (stack.getType() == Material.ENDER_PEARL) {
            tracker.pearlsPickedUp(player.getUniqueId(), stack.getAmount(), pearlTarget.getAsInt());
        } else if (stack.getType() == Material.BLAZE_ROD) {
            tracker.reach(SpeedrunMilestones.BLAZE_ROD.id(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !inRunsEnd(dragon)) {
            return;
        }
        if (dragon.getHealth() - event.getFinalDamage() <= maxHealth.applyAsDouble(dragon) / 2) {
            tracker.reach(SpeedrunMilestones.DRAGON_HALF.id(), damager(event));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon && inRunsEnd(dragon)) {
            Player killer = dragon.getKiller();
            tracker.reach(SpeedrunMilestones.DRAGON_HALF.id(), killer == null ? null : killer.getUniqueId());
            tracker.reach(SpeedrunMilestones.DRAGON_KILL.id(), killer == null ? null : killer.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // Every racer's death is on the record, whoever counts for the splits.
        if (!session.participants().contains(player.getUniqueId()) || session.state() == SpeedrunState.FINISHED) {
            return;
        }
        Component message = event.deathMessage();
        String said = message == null ? "" : PlainTextComponentSerializer.plainText().serialize(message);
        session.timeline().record(SpeedrunTimeline.Kind.DEATH, session.elapsed(), player.getUniqueId(), said);
    }

    private boolean racing(Player player) {
        return session.participants().contains(player.getUniqueId()) && counts.test(player.getUniqueId());
    }

    private boolean inRunsEnd(Entity entity) {
        return worlds.theEnd().equalsIgnoreCase(nameOf(entity.getWorld()));
    }

    /** Whoever dealt it — a player, or the player who loosed the arrow — or nobody's. */
    private static UUID damager(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity source = byEntity.getDamager();
            if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
                return shooter.getUniqueId();
            }
            if (source instanceof Player player) {
                return player.getUniqueId();
            }
        }
        return null;
    }

    private static String nameOf(World world) {
        return world == null ? null : world.getName();
    }
}
