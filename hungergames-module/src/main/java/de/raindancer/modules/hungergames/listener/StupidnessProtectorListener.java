package de.raindancer.modules.hungergames.listener;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.UUID;

/**
 * Catching a hit that would otherwise kill somebody, for whoever is carrying a Stupidness Protector.
 *
 * <h2>Why this exists instead of an ability</h2>
 * {@code SurvivalItemService} registers the Stupidness Protector as a passive item with no ability behind
 * it at all — see that class's javadoc for the full argument. In short: Core's {@code ItemTrigger} has a
 * {@code LETHAL_DAMAGE} case that reads like the obvious fit, and is not: it would fire on <em>every</em>
 * lethal hit, including another tribute's kill, and the whole point of this item is that it must not save
 * anybody from one of those. Only a real listener holding the actual {@link EntityDamageEvent} can tell a
 * lava death from a sword blow, so this class exists to be exactly that listener and nothing more —
 * {@code SurvivalItemService.wouldSaveFrom} still makes every real decision: whether the holder actually
 * has a protector to spend, and whether this cause is one it saves against at all.
 *
 * <h2>Why {@code HIGHEST} and {@code ignoreCancelled}</h2>
 * The source plugin's own listener used this same priority, for the same reason: it must see the damage
 * after every other plugin has had its say (armour, enchantments, a shield block that already reduced the
 * hit to something survivable), and it must not act on a hit something else already cancelled — cancelling
 * an already-cancelled event and consuming a protector for a blow that was never going to land would be a
 * bug that only ever shows up as "my protector vanished for no reason".
 */
public final class StupidnessProtectorListener implements IHungerGamesListener {

    /** Deciding whether a holder's protector saves them from this cause, and spending it if so. */
    @FunctionalInterface
    public interface Rescue {

        /** @return whether the holder was saved — the damage should be cancelled exactly when this is true */
        boolean wouldSaveFrom(UUID holder, String causeName);
    }

    private final Rescue rescue;

    public StupidnessProtectorListener(Rescue rescue) {
        this.rescue = rescue;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player: every decision reads the event itself, at the moment of the
        // hit, so there is nothing here that could leak across a rejoin.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.getHealth() - event.getFinalDamage() > 0) {
            return;   // not lethal — the protector only ever spends itself on a hit that would actually kill
        }
        if (rescue.wouldSaveFrom(player.getUniqueId(), causeOf(event))) {
            event.setCancelled(true);
        }
    }

    /**
     * The cause, in the spelling {@code SurvivalItemService.STUPIDNESS_EXCLUDED_CAUSES} compares against.
     *
     * <p>{@code "PLAYER"} whenever the damager is a player or a projectile a player fired — that covers
     * both an ordinary sword hit and this module's own lightning-based items, because every one of them
     * deals its damage through {@code LivingEntity#damage(amount, sourcePlayer)} with the caster as the
     * source. Bukkit's own {@link EntityDamageEvent.DamageCause} cannot tell "a mob" from "a tribute's
     * custom weapon" — both arrive as {@code ENTITY_ATTACK} — which is exactly why the source plugin, and
     * this port, ask who the damager <em>is</em> rather than trust the enum alone. Everything else is the
     * plain cause name: lava, a fall, fire, drowning, an ordinary mob — the "environmental pech" this item
     * exists to forgive.
     */
    private static String causeOf(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            var damager = byEntity.getDamager();
            if (damager instanceof Player) {
                return "PLAYER";
            }
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player) {
                return "PLAYER";
            }
        }
        return event.getCause().name();
    }

    @Override
    public String describe() {
        return "catching a lethal environmental hit for whoever holds a stupidness protector";
    }
}
