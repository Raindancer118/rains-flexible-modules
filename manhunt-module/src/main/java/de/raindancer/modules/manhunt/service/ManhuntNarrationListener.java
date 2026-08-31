package de.raindancer.modules.manhunt.service;

import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * The four events the narration is made of. Nothing is decided here — {@link ManhuntNarrator} holds
 * the settings and the once-only marks, the same converter/decider split the rest of this module has.
 *
 * <h2>{@link PlayerChangedWorldEvent}, not {@code PlayerPortalEvent}</h2>
 * The tracking compass listens to the portal event because it wants the door, on the side the Hunters
 * are still standing on. The narration wants the arrival, and a Runner reaches another world by more
 * routes than a portal they walked through — an End gateway, a plugin teleport, a death and a respawn
 * elsewhere. This event fires for all of them, after the fact, which is exactly when there is
 * something to announce.
 */
public final class ManhuntNarrationListener implements Listener {

    private final ManhuntService manhunt;
    private final ManhuntLives lives;
    private final ManhuntNarrator narrator;

    public ManhuntNarrationListener(ManhuntService manhunt, ManhuntLives lives, ManhuntNarrator narrator) {
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.lives = Objects.requireNonNull(lives, "lives");
        this.narrator = Objects.requireNonNull(narrator, "narrator");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!manhunt.isRunning() || !manhunt.teams().isRunner(player.getUniqueId())) {
            return;
        }
        World now = player.getWorld();
        if (now.equals(event.getFrom())) {
            return;
        }
        narrator.runnerChangedWorld(player.getName(), friendly(now));
    }

    /**
     * Reads the board rather than the event: {@code ManhuntDeathListener} records the death at
     * {@code HIGH} and this runs at {@code MONITOR}, so by now "how many lives are left" is already
     * the answer after this death rather than before it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        if (!manhunt.isRunning()) {
            return;
        }
        if (manhunt.teams().isHunter(id)) {
            narrator.hunterDied(player.getName());
            return;
        }
        if (!manhunt.teams().isRunner(id)) {
            return;
        }
        if (lives.isOut(id)) {
            narrator.runnerEliminated(player.getName(), lives.stillIn(manhunt.teams().runners()).size());
        } else {
            narrator.runnerDied(player.getName(), lives.livesLeft(id));
        }
    }

    /**
     * The dragon's health, before and after the hit. {@link EntityDamageEvent#getFinalDamage()} is
     * what actually lands after armour and resistances, so the "after" here is the number the dragon
     * will really be on — reading its health back a tick later would work too, and would announce
     * half a second after everybody watching already saw the bar move.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonHit(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !manhunt.isRunning()) {
            return;
        }
        var maxHealth = dragon.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (maxHealth == null || maxHealth.getValue() <= 0) {
            return;
        }
        double max = maxHealth.getValue();
        double was = dragon.getHealth() / max;
        double now = Math.max(0, dragon.getHealth() - event.getFinalDamage()) / max;
        narrator.dragonHealth(was, now);
    }

    /** A dragon that died is done; a later one is a fresh fight with its own two moments to announce. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon) {
            narrator.dragonReset();
        }
    }

    /** "world_nether" is a folder name; the Nether is a place. */
    private static String friendly(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "the Nether";
            case THE_END -> "the End";
            case NORMAL -> "the Overworld";
            default -> world.getName();
        };
    }

    public String describe() {
        return "the events the narration is made of: dimensions, deaths and the dragon";
    }
}
